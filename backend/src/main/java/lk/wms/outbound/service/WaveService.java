package lk.wms.outbound.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.domain.PickLine;
import lk.wms.outbound.domain.PickList;
import lk.wms.outbound.domain.PickWave;
import lk.wms.outbound.domain.SalesOrder;
import lk.wms.outbound.repo.PickListRepository;
import lk.wms.outbound.repo.PickWaveRepository;
import lk.wms.outbound.repo.SalesOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch picking (v1.2 task 1). A wave walks several orders' pick lists at once: one combined walk in bin order, each
 * order into its own tote. Picking itself still goes through each list (PickService), so stock, reservations and
 * retries work exactly as for single orders. Locks: pick lists (by id) first, then the wave, as a pick does.
 */
@Service
public class WaveService {

    public record WalkLine(String listId, int seq, int tote, String orderNumber, String sku, String productName, String baseUom,
            String binCode, String batchNo, LocalDate expiryDate, BigDecimal qtyToPick, BigDecimal qtyPicked, String status) {
    }

    public record Order(String listId, String listNumber, int tote, String orderNumber, String customerName, String status, int openLines) {
    }

    /** One wave: its orders, the combined walk, and how far it is compared with picking the orders one by one. */
    public record WaveView(String id, String number, String status, String warehouseCode, LocalDate cutOff, int version,
            PickService.Person assignee, List<Order> orders, List<WalkLine> walk, int openLines, double walkMetres,
            double singleWalksMetres) {
    }

    public record Summary(String id, String number, String status, String warehouseCode, int orders, int openLines,
            boolean urgent, PickService.Person assignee) {
    }

    static final String KIND = "WV";
    static final int MAX_ORDERS = 20; // totes on a trolley

    private final PickWaveRepository waves;
    private final PickListRepository lists;
    private final SalesOrderRepository orders;
    private final PickService picks;
    private final DocNumbers numbers;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final AuditService audit;
    private final lk.wms.auth.service.UserService users;

    public WaveService(PickWaveRepository waves, PickListRepository lists, SalesOrderRepository orders, PickService picks,
            DocNumbers numbers, WarehouseService warehouses, BinService bins, AuditService audit, lk.wms.auth.service.UserService users) {
        this.waves = waves;
        this.lists = lists;
        this.orders = orders;
        this.picks = picks;
        this.numbers = numbers;
        this.warehouses = warehouses;
        this.bins = bins;
        this.audit = audit;
        this.users = users;
    }

    /** A wave of these open pick lists (2 to 20, one warehouse, not started, not in another wave). */
    @Transactional
    public WaveView create(CurrentUser cu, String warehouseId, List<String> listIds, String pickerId) {
        return make(cu, warehouseId, listIds, null, pickerId);
    }

    /** "Wave by cut-off": open lists whose order is wanted by this date (or has no date), oldest first, at most max. */
    @Transactional
    public WaveView byCutOff(CurrentUser cu, String warehouseId, LocalDate cutOff, int max, String pickerId) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        List<String> ids = lists.openForWave(wh.id(), cutOff, org.springframework.data.domain.PageRequest.of(0, Math.min(max, MAX_ORDERS)));
        if (ids.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WAVE_TOO_SMALL", "Fewer than 2 open orders are wanted by " + cutOff);
        }
        return make(cu, warehouseId, ids, cutOff, pickerId);
    }

    private WaveView make(CurrentUser cu, String warehouseId, List<String> listIds, LocalDate cutOff, String pickerId) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        List<String> distinct = listIds.stream().distinct().toList();
        if (distinct.size() < 2 || distinct.size() > MAX_ORDERS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WAVE_SIZE", "A wave takes 2 to " + MAX_ORDERS + " orders");
        }
        List<PickList> locked = lists.lockByPublicIds(distinct);
        if (locked.size() != distinct.size() || locked.stream().anyMatch(p -> p.getWarehouseId() != wh.id())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PICK_LIST_NOT_FOUND", "A pick list was not found in this warehouse");
        }
        List<String> busy = locked.stream().filter(p -> p.getStatus() != PickList.Status.OPEN || p.getWaveId() != null)
                .map(PickList::getNumber).toList();
        if (!busy.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "LIST_NOT_FREE", "Already started or in another wave: " + String.join(", ", busy));
        }
        PickWave wave = waves.saveAndFlush(new PickWave(numbers.next(cu, KIND), wh.id(), cutOff, cu.userId()));
        int tote = 1;
        for (PickList p : locked) { // tote numbers in list order
            p.joinWave(wave.getId(), tote++);
        }
        lists.flush();
        if (pickerId != null && !pickerId.isBlank()) {
            locked.forEach(p -> picks.assign(cu, p.getPublicId(), pickerId)); // checks the person may pick here
        }
        audit.record(cu.tenantId(), cu.userId(), "WAVE_CREATED", "pick_wave", wave.getPublicId(), null,
                Map.of("number", wave.getNumber(), "lists", locked.stream().map(PickList::getNumber).toList(),
                        "cutOff", cutOff == null ? "" : cutOff.toString()));
        return view(wave);
    }

    /** Open waves of my warehouses; a picker sees waves given to them or to nobody. */
    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        Long forUser = cu.permissions().contains(PickService.ASSIGN) ? null : cu.userId();
        Page<PickWave> page = waves.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids,
                List.of(PickWave.Status.OPEN, PickWave.Status.IN_PROGRESS), pageable);
        List<Summary> out = new ArrayList<>();
        for (PickWave w : page.getContent()) {
            List<PickList> members = lists.findByWaveIdOrderByTote(w.getId());
            Long assigned = members.isEmpty() ? null : members.get(0).getAssignedTo();
            if (forUser != null && assigned != null && !assigned.equals(forUser)) {
                continue;
            }
            out.add(new Summary(w.getPublicId(), w.getNumber(), w.getStatus().name(), warehouses.codeOf(w.getWarehouseId()), members.size(),
                    members.stream().mapToInt(WaveService::open).sum(),
                    members.stream().anyMatch(m -> m.getPriority() == PickList.Priority.URGENT), person(assigned)));
        }
        return new org.springframework.data.domain.PageImpl<>(out, pageable, out.size());
    }

    @Transactional(readOnly = true)
    public WaveView get(CurrentUser cu, String publicId) {
        return view(visible(cu, waves.findByPublicId(publicId).orElse(null)));
    }

    /** Takes the wave apart while nothing in it is picked: the orders go back to single lists. */
    @Transactional
    public void dissolve(CurrentUser cu, String publicId) {
        PickWave seen = visible(cu, waves.findByPublicId(publicId).orElse(null));
        List<PickList> locked = lists.lockByPublicIds(lists.findByWaveIdOrderByTote(seen.getId()).stream().map(PickList::getPublicId).toList());
        PickWave wave = waves.lockById(seen.getId()).orElseThrow(); // lists first, then the wave, as a pick does
        if (locked.stream().anyMatch(p -> p.getStatus() != PickList.Status.OPEN)) {
            throw new ApiException(HttpStatus.CONFLICT, "WAVE_STARTED", "Picking has started in this wave");
        }
        locked.forEach(PickList::leaveWave);
        lists.flush();
        waves.delete(wave);
        audit.record(cu.tenantId(), cu.userId(), "WAVE_DISSOLVED", "pick_wave", wave.getPublicId(), null,
                Map.of("number", wave.getNumber(), "lists", locked.stream().map(PickList::getNumber).toList()));
    }

    // ---------------------------------------------------------------- helpers

    private PickWave visible(CurrentUser cu, PickWave w) {
        List<Long> ids = w == null ? null : warehouses.assignedIds(cu);
        if (w == null || (ids != null && !ids.contains(w.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WAVE_NOT_FOUND", "Wave not found");
        }
        return w;
    }

    private WaveView view(PickWave w) {
        List<PickList> members = lists.findByWaveIdOrderByTote(w.getId());
        Map<Long, SalesOrder> byId = new HashMap<>();
        orders.byInternalIds(members.stream().map(PickList::getOrderId).toList()).forEach(o -> byId.put(o.getId(), o));
        List<Order> orderViews = new ArrayList<>();
        List<WalkLine> walk = new ArrayList<>();
        Map<String, Long> binIds = new HashMap<>();
        double singles = 0;
        for (PickList p : members) {
            SalesOrder o = byId.get(p.getOrderId());
            orderViews.add(new Order(p.getPublicId(), p.getNumber(), p.getTote(), o.getNumber(), o.getCustomerName(), p.getStatus().name(), open(p)));
            for (PickLine l : p.getLines()) {
                walk.add(new WalkLine(p.getPublicId(), l.getSeq(), p.getTote(), o.getNumber(), l.getSku(), l.getProductName(), l.getBaseUom(),
                        l.getBinCode(), l.getBatchNo(), l.getExpiryDate(), l.getQtyToPick(), l.getQtyPicked(), l.getStatus().name()));
                binIds.put(l.getBinCode(), l.getBinId());
            }
            singles += bins.walkMetres(w.getWarehouseId(), p.getLines().stream().map(PickLine::getBinId).toList());
        }
        walk.sort(Comparator.comparing(WalkLine::binCode).thenComparingInt(WalkLine::tote).thenComparingInt(WalkLine::seq));
        double together = bins.walkMetres(w.getWarehouseId(), walk.stream().map(l -> binIds.get(l.binCode())).distinct().toList());
        Long assigned = members.isEmpty() ? null : members.get(0).getAssignedTo();
        return new WaveView(w.getPublicId(), w.getNumber(), w.getStatus().name(), warehouses.codeOf(w.getWarehouseId()), w.getCutOff(),
                w.getVersion(), person(assigned), orderViews, walk, members.stream().mapToInt(WaveService::open).sum(), together,
                Math.round(singles * 10) / 10.0);
    }

    private PickService.Person person(Long userId) {
        var u = userId == null ? null : users.viewsByInternalId(List.of(userId)).get(userId);
        return u == null ? null : new PickService.Person(u.id(), u.fullName());
    }

    private static int open(PickList p) {
        return (int) p.getLines().stream().filter(l -> l.getStatus() == PickLine.Status.OPEN).count();
    }
}
