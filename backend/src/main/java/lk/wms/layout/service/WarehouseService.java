package lk.wms.layout.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.wms.auth.service.UserService;
import lk.wms.auth.service.UserService.UserView;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.UserWarehouse;
import lk.wms.layout.domain.Warehouse;
import lk.wms.layout.repo.UserWarehouseRepository;
import lk.wms.layout.repo.WarehouseRepository;
import lk.wms.tenant.service.TenantService;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouses of one company, and who may see them (SEC-10). A warehouse the caller may not see is
 * "not found", exactly like one in another company, so IDs reveal nothing.
 */
@Service
public class WarehouseService {

    /** API view. Sizes in millimetres, as stored; the UI shows metres. */
    public record WarehouseView(String id, String code, String name, String layoutStatus, int widthMm, int lengthMm,
            int wallHeightMm, String floorPolygon, Instant createdAt) {
    }

    /** What other modules (inventory, outbound) need after an access check. */
    public record WarehouseRef(long id, String publicId, String code) {
    }

    record Snapshot(String code, String name, int wallHeightMm) {
    }

    private final WarehouseRepository warehouses;
    private final UserWarehouseRepository access;
    private final TenantService tenants;
    private final UserService users;
    private final AuditService audit;

    public WarehouseService(WarehouseRepository warehouses, UserWarehouseRepository access, TenantService tenants,
            UserService users, AuditService audit) {
        this.warehouses = warehouses;
        this.access = access;
        this.tenants = tenants;
        this.users = users;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Page<WarehouseView> list(CurrentUser cu, Pageable pageable) {
        Page<Warehouse> page = cu.isTenantAdmin() ? warehouses.findAll(pageable)
                : warehouses.findAssigned(cu.userId(), pageable);
        return page.map(WarehouseService::view);
    }

    @Transactional(readOnly = true)
    public WarehouseView get(CurrentUser cu, String publicId) {
        return view(require(cu, publicId));
    }

    /** For other modules: the warehouse if this user may work in it, else 404. */
    @Transactional(readOnly = true)
    public WarehouseRef requireAccess(CurrentUser cu, String publicId) {
        Warehouse w = require(cu, publicId);
        return new WarehouseRef(w.getId(), w.getPublicId(), w.getCode());
    }

    /** Same, by warehouse code (CSV imports name warehouses by code). */
    @Transactional(readOnly = true)
    public WarehouseRef requireAccessByCode(CurrentUser cu, String code) {
        Warehouse w = warehouses.findByCode(code)
                .filter(found -> cu.isTenantAdmin() || warehouses.isAssigned(found.getId(), cu.userId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND", "Warehouse " + code + " not found"));
        return new WarehouseRef(w.getId(), w.getPublicId(), w.getCode());
    }

    /** Public id of a warehouse, or null once it is archived. */
    @Transactional(readOnly = true)
    public String publicIdOf(long warehouseId) {
        return warehouses.publicIdOf(warehouseId).orElse(null);
    }

    /** Code of a warehouse, or null once it is archived. */
    @Transactional(readOnly = true)
    public String codeOf(long warehouseId) {
        return warehouses.codeOf(warehouseId).orElse(null);
    }

    /** Internal ids of the company's warehouses (archived ones excluded), for jobs. */
    @Transactional(readOnly = true)
    public List<Long> liveIds() {
        return warehouses.findAll().stream().map(Warehouse::getId).toList();
    }

    /** Users assigned to a warehouse (internal ids); company admins work everywhere and are not listed. */
    @Transactional(readOnly = true)
    public List<Long> userIdsIn(long warehouseId) {
        return access.findUserIds(warehouseId);
    }

    /** Warehouses this user may work in, or null for a company admin (all of them). For filtering lists. */
    @Transactional(readOnly = true)
    public List<Long> assignedIds(CurrentUser cu) {
        return cu.isTenantAdmin() ? null : access.findWarehouseIds(cu.userId());
    }

    @Transactional(readOnly = true)
    public void requireAccess(CurrentUser cu, long warehouseId) {
        boolean ok = warehouses.existsActive(warehouseId) // a query: the tenant filter applies
                && (cu.isTenantAdmin() || warehouses.isAssigned(warehouseId, cu.userId()));
        if (!ok) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND", "Warehouse not found");
        }
    }

    // ---------------------------------------------------------------- changes

    @Transactional
    public WarehouseView create(CurrentUser cu, String code, String name, int widthMm, int lengthMm, int wallHeightMm) {
        int max = tenants.lockMaxWarehouses(cu.tenantId()); // held until commit: no race past the limit
        long existing = warehouses.countActive();
        if (existing >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_LIMIT",
                    "Your plan allows " + max + " warehouse(s). Archive one or upgrade the plan.");
        }
        Warehouse w;
        try {
            w = warehouses.saveAndFlush(new Warehouse(code, name.trim(), widthMm, lengthMm, wallHeightMm, cu.userId()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CODE_IN_USE", "Another warehouse already uses this code");
        }
        // The creator can always see it; the company's first warehouse is open to everyone.
        Set<Long> userIds = new LinkedHashSet<>(existing == 0 ? users.allUserIds() : List.of());
        userIds.add(cu.userId());
        userIds.forEach(id -> access.save(new UserWarehouse(id, w.getId())));
        audit.record(cu.tenantId(), cu.userId(), "WAREHOUSE_CREATED", "warehouse", w.getPublicId(), null, snapshot(w));
        return view(w);
    }

    @Transactional
    public WarehouseView update(CurrentUser cu, String publicId, String name, Integer wallHeightMm) {
        Warehouse w = require(cu, publicId);
        Snapshot before = snapshot(w);
        w.update(name == null ? null : name.trim(), wallHeightMm);
        audit.record(cu.tenantId(), cu.userId(), "WAREHOUSE_UPDATED", "warehouse", w.getPublicId(), before, snapshot(w));
        return view(w);
    }

    /** ponytail: refuses nothing yet; from v0.4 a warehouse holding stock cannot be archived. */
    @Transactional
    public void archive(CurrentUser cu, String publicId) {
        Warehouse w = require(cu, publicId);
        w.archive();
        audit.record(cu.tenantId(), cu.userId(), "WAREHOUSE_ARCHIVED", "warehouse", w.getPublicId(), snapshot(w), null);
    }

    // ---------------------------------------------------------------- access lists

    @Transactional(readOnly = true)
    public List<UserView> assignedUsers(CurrentUser cu, String publicId) {
        Warehouse w = require(cu, publicId);
        Map<Long, UserView> views = users.viewsByInternalId(access.findUserIds(w.getId()));
        return views.values().stream().sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName())).toList();
    }

    /** Replace who may see this warehouse. Unknown or other-company user IDs are refused. */
    @Transactional
    public List<UserView> setAssignedUsers(CurrentUser cu, String publicId, List<String> userPublicIds) {
        Warehouse w = require(cu, publicId);
        List<Long> before = access.findUserIds(w.getId());
        List<Long> ids = new ArrayList<>();
        for (String userId : new LinkedHashSet<>(userPublicIds)) {
            Long id = users.internalId(userId); // tenant-filtered: another company's user is null
            if (id == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "USER_UNKNOWN", "One or more users do not exist");
            }
            ids.add(id);
        }
        access.deleteAllForWarehouse(w.getId());
        ids.forEach(id -> access.save(new UserWarehouse(id, w.getId())));
        audit.record(cu.tenantId(), cu.userId(), "WAREHOUSE_ACCESS_CHANGED", "warehouse", w.getPublicId(),
                Map.of("userIds", before.size()), Map.of("userIds", ids.size()));
        return assignedUsers(cu, publicId);
    }

    /** While a company has exactly one warehouse, new people can work there without extra setup. */
    @EventListener
    public void onUserInvited(UserService.UserInvited event) {
        List<Warehouse> all = warehouses.findAll();
        if (all.size() == 1) {
            access.save(new UserWarehouse(event.userId(), all.get(0).getId()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Warehouse require(CurrentUser cu, String publicId) {
        Warehouse w = warehouses.findByPublicId(publicId)
                .filter(found -> cu.isTenantAdmin() || warehouses.isAssigned(found.getId(), cu.userId()))
                .orElse(null);
        if (w == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND", "Warehouse not found");
        }
        return w;
    }

    private static WarehouseView view(Warehouse w) {
        int[] size = bounds(w.getFloorPolygon());
        return new WarehouseView(w.getPublicId(), w.getCode(), w.getName(), w.getLayoutStatus().name(), size[0], size[1],
                w.getWallHeightMm(), w.getFloorPolygon(), w.getCreatedAt());
    }

    /** Width and length of the floor's bounding box, from the stored [[x,y],...] outline. */
    static int[] bounds(String polygonJson) {
        int maxX = 0;
        int maxY = 0;
        String[] numbers = polygonJson.replaceAll("[\\[\\]\\s]", "").split(",");
        for (int i = 0; i + 1 < numbers.length; i += 2) {
            maxX = Math.max(maxX, Integer.parseInt(numbers[i]));
            maxY = Math.max(maxY, Integer.parseInt(numbers[i + 1]));
        }
        return new int[] {maxX, maxY};
    }

    private static Snapshot snapshot(Warehouse w) {
        return new Snapshot(w.getCode(), w.getName(), w.getWallHeightMm());
    }
}
