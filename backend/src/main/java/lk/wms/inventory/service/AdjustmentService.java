package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.domain.StockAdjustment;
import lk.wms.inventory.domain.StockAdjustment.Direction;
import lk.wms.inventory.domain.StockAdjustment.Status;
import lk.wms.inventory.repo.StockAdjustmentRepository;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.tenant.service.TenantService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock adjustments (v0.4 task 7): stock found, lost, damaged or written off, always with a reason. An adjustment
 * worth up to the company's limit (default LKR 10,000) is applied at once; a bigger one, or one for a product with
 * no cost (its value is unknown), waits for someone with inventory:approve. Someone who may approve has their own
 * adjustments applied at once. Applying goes through StockMovementService like every other stock change.
 */
@Service
public class AdjustmentService {

    public record Request(String warehouseId, String binCode, String productId, String batchNo, LocalDate expiryDate,
            Direction direction, BigDecimal qty, StockAdjustment.Reason reason, String note, String clientRef) {
    }

    public record AdjustmentView(String id, String number, String status, String warehouseCode, String binCode, String sku,
            String productName, String baseUom, String batchNo, LocalDate expiryDate, String direction, BigDecimal qty,
            String reason, String note, Long valueMinor, String requestedBy, Instant requestedAt, String decidedBy,
            Instant decidedAt, String decisionNote, int version) {
    }

    static final String KIND = "ADJ";

    private final StockAdjustmentRepository adjustments;
    private final StockMovementService moves;
    private final WarehouseService warehouses;
    private final ProductService products;
    private final TenantService tenants;
    private final DocNumbers numbers;
    private final Lots lots;
    private final AuditService audit;
    private final lk.wms.auth.service.UserService users;

    public AdjustmentService(StockAdjustmentRepository adjustments, StockMovementService moves, WarehouseService warehouses,
            ProductService products, TenantService tenants, DocNumbers numbers, Lots lots, AuditService audit,
            lk.wms.auth.service.UserService users) {
        this.adjustments = adjustments;
        this.moves = moves;
        this.warehouses = warehouses;
        this.products = products;
        this.tenants = tenants;
        this.numbers = numbers;
        this.lots = lots;
        this.audit = audit;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<AdjustmentView> list(CurrentUser cu, Collection<Status> statuses, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        Page<StockAdjustment> page = adjustments.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids,
                statuses, pageable);
        return page.map(this::view);
    }

    @Transactional
    public AdjustmentView request(CurrentUser cu, Request r) {
        StockAdjustment again = adjustments.findByClientRef(r.clientRef()).orElse(null);
        if (again != null) {
            return view(visible(cu, again)); // the phone retried
        }
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, r.warehouseId());
        ProductService.Basic p = products.basic(r.productId());
        BinService.BinRef bin = lots.bin(cu, w.id(), r.binCode());
        Long binId = bin == null ? null : bin.id();
        BigDecimal qty = r.qty().setScale(3, RoundingMode.UNNECESSARY);
        Lots.Lot lot = r.direction() == Direction.OUT
                ? lots.existing(w.id(), binId, p.id(), r.batchNo(), r.expiryDate())
                : new Lots.Lot(Lots.normal(r.batchNo()), r.expiryDate());
        Long value = p.costMinor() == null ? null
                : qty.multiply(BigDecimal.valueOf(p.costMinor())).setScale(0, RoundingMode.HALF_UP).longValueExact();
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        StockAdjustment a = adjustments.save(new StockAdjustment(numbers.next(cu, KIND), new StockAdjustment.Spec(w.id(), binId,
                bin == null ? null : bin.code(), p.id(), p.sku(), lot.batchNo(), lot.expiryDate(), r.direction(), qty,
                r.reason(), note, value, r.clientRef()), cu.userId()));
        boolean small = value != null && value <= tenants.adjustApprovalLimit(cu.tenantId());
        audit.record(cu.tenantId(), cu.userId(), "ADJUSTMENT_REQUESTED", "stock_adjustment", a.getPublicId(), null,
                Map.of("number", a.getNumber(), "sku", p.sku(), "direction", r.direction().name(), "qty", qty,
                        "value", value == null ? "unknown" : value));
        if (small || cu.permissions().contains("inventory:approve")) {
            apply(cu, a, small ? "Within the approval limit" : "Requested by an approver");
        }
        adjustments.flush();
        return view(a);
    }

    @Transactional
    public AdjustmentView approve(CurrentUser cu, String id, int version, String note) {
        StockAdjustment a = pending(cu, id, version);
        apply(cu, a, blank(note));
        adjustments.flush();
        return view(a);
    }

    @Transactional
    public AdjustmentView reject(CurrentUser cu, String id, int version, String note) {
        StockAdjustment a = pending(cu, id, version);
        a.decide(Status.REJECTED, cu.userId(), note.trim());
        adjustments.flush();
        audit.record(cu.tenantId(), cu.userId(), "ADJUSTMENT_REJECTED", "stock_adjustment", a.getPublicId(), null,
                Map.of("number", a.getNumber(), "note", a.getDecisionNote()));
        return view(a);
    }

    private void apply(CurrentUser cu, StockAdjustment a, String note) {
        StockMovementService.Place place = a.getBinId() == null ? StockMovementService.Place.RECEIVING
                : StockMovementService.Place.bin(a.getBinId());
        boolean out = a.getDirection() == Direction.OUT;
        moves.move(new StockMovementService.Move(StockMovementService.Kind.ADJUST, a.getWarehouseId(), a.getProductId(),
                a.getBatchNo(), a.getExpiryDate(), out ? place : null, out ? null : place, a.getQty(),
                a.getReason().name() + (a.getNote() == null ? "" : ": " + a.getNote()), "adjustment", a.getId(), cu.userId()));
        a.decide(Status.APPLIED, cu.userId(), note);
        audit.record(cu.tenantId(), cu.userId(), "ADJUSTMENT_APPLIED", "stock_adjustment", a.getPublicId(), null,
                Map.of("number", a.getNumber()));
    }

    private StockAdjustment pending(CurrentUser cu, String id, int version) {
        StockAdjustment a = visible(cu, adjustments.findByPublicId(id).orElse(null));
        if (a.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "ADJUSTMENT_CHANGED", "This adjustment changed; reload it");
        }
        if (a.getStatus() != Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "ADJUSTMENT_DECIDED", "This adjustment is already " + a.getStatus().name().toLowerCase());
        }
        return a;
    }

    private StockAdjustment visible(CurrentUser cu, StockAdjustment a) {
        List<Long> ids = a == null ? null : warehouses.assignedIds(cu);
        if (a == null || (ids != null && !ids.contains(a.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ADJUSTMENT_NOT_FOUND", "Adjustment not found");
        }
        return a;
    }

    private AdjustmentView view(StockAdjustment a) {
        ProductService.Basic p = products.basics(List.of(a.getProductId())).get(a.getProductId());
        Map<Long, lk.wms.auth.service.UserService.UserView> people = users.viewsByInternalId(
                a.getDecidedBy() == null ? List.of(a.getRequestedBy()) : List.of(a.getRequestedBy(), a.getDecidedBy()));
        var requester = people.get(a.getRequestedBy());
        var decider = a.getDecidedBy() == null ? null : people.get(a.getDecidedBy());
        String whCode = warehouses.codeOf(a.getWarehouseId());
        return new AdjustmentView(a.getPublicId(), a.getNumber(), a.getStatus().name(), whCode, a.getBinCode(), a.getSku(),
                p == null ? "" : p.name(), p == null ? "" : p.baseUom(), a.getBatchNo(), a.getExpiryDate(),
                a.getDirection().name(), a.getQty(), a.getReason().name(), a.getNote(), a.getValueMinor(),
                requester == null ? null : requester.fullName(), a.getRequestedAt(), decider == null ? null : decider.fullName(),
                a.getDecidedAt(), a.getDecisionNote(), a.getVersion());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
