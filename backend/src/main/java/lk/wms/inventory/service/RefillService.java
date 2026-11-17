package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.domain.RefillRule;
import lk.wms.inventory.domain.RefillTask;
import lk.wms.inventory.domain.Stock;
import lk.wms.inventory.repo.RefillRepository;
import lk.wms.inventory.repo.RefillTaskRepository;
import lk.wms.inventory.repo.StockRepository;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeping pick bins full (v1.2 task 3). A rule keeps between min and max of a product in a pick-face bin. When stock
 * leaves that bin and it drops below min, a refill task asks for max - on hand to be brought from bulk storage
 * (oldest expiry first, never from another pick face, a blocked bin or quarantine). Doing the task is a TRANSFER
 * through StockMovementService. The rule row is locked while checking, so two picks at once make one task.
 */
@Service
public class RefillService {

    public record RuleView(String id, String binId, String binCode, String productId, String sku, String productName, String baseUom,
            BigDecimal minQty, BigDecimal maxQty, boolean active, BigDecimal onHand, String openTask, int version) {
    }

    /** Where to take from: the bin, the lot and how many are free there. */
    public record Source(String binCode, String batchNo, LocalDate expiryDate, BigDecimal free) {
    }

    public record TaskView(String id, String number, String status, String warehouseCode, String binCode, String sku, String productName,
            String baseUom, BigDecimal qty, BigDecimal movedQty, Source from, Instant createdAt) {
    }

    static final String KIND = "RF";
    static final String REF = "REFILL";

    private final RefillRepository rules;
    private final RefillTaskRepository tasks;
    private final StockRepository stock;
    private final StockMovementService moves;
    private final DocNumbers numbers;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final ProductService products;
    private final AuditService audit;

    public RefillService(RefillRepository rules, RefillTaskRepository tasks, StockRepository stock, StockMovementService moves,
            DocNumbers numbers, WarehouseService warehouses, BinService bins, ProductService products, AuditService audit) {
        this.rules = rules;
        this.tasks = tasks;
        this.stock = stock;
        this.moves = moves;
        this.numbers = numbers;
        this.warehouses = warehouses;
        this.bins = bins;
        this.products = products;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- automatic check

    /** Stock left a bin: if it is a pick face with a rule and now below min, make a task (same transaction). */
    @EventListener
    public void onStockTaken(StockMovementService.StockTaken e) {
        rules.lockActive(e.binId(), e.productId()).ifPresent(rule -> checkRule(rule, e.userId()));
    }

    /** Makes the task when the rule's bin is below min and none is open. Returns true when it made one. */
    private boolean checkRule(RefillRule rule, long userId) {
        if (!rule.isActive() || tasks.existsByRuleIdAndStatus(rule.getId(), RefillTask.Status.OPEN)) {
            return false;
        }
        BigDecimal onHand = onHand(rule);
        if (onHand.compareTo(rule.getMinQty()) >= 0) {
            return false;
        }
        long tenantId = lk.wms.common.tenant.TenantContext.require();
        RefillTask t = tasks.saveAndFlush(new RefillTask(numbers.next(tenantId, KIND), rule, rule.getMaxQty().subtract(onHand)));
        audit.record(tenantId, userId, "REFILL_TASK_CREATED", "refill_task", t.getPublicId(), null,
                Map.of("number", t.getNumber(), "qty", t.getQty()));
        return true;
    }

    private BigDecimal onHand(RefillRule rule) {
        return stock.lots(rule.getWarehouseId(), rule.getBinId(), rule.getProductId()).stream().map(Stock::getQtyOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** "Check now": makes tasks for every rule of the warehouse that is below min. */
    @Transactional
    public int checkNow(CurrentUser cu, String warehouseId) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        int made = 0;
        for (RefillRule r : rules.inWarehouse(wh.id())) {
            if (r.isActive() && rules.lockActive(r.getBinId(), r.getProductId()).map(x -> checkRule(x, cu.userId())).orElse(false)) {
                made++;
            }
        }
        return made;
    }

    // ---------------------------------------------------------------- rules

    @Transactional(readOnly = true)
    public List<RuleView> rules(CurrentUser cu, String warehouseId) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        List<RefillRule> list = rules.inWarehouse(wh.id());
        Map<Long, String> open = new HashMap<>();
        tasks.openFor(list.stream().map(RefillRule::getId).toList().isEmpty() ? List.of(-1L) : list.stream().map(RefillRule::getId).toList())
                .forEach(t -> open.put(t.getRuleId(), t.getNumber()));
        return views(list, open);
    }

    /** A rule on a pick-face bin of the warehouse; one per bin and product. */
    @Transactional
    public RuleView create(CurrentUser cu, String warehouseId, String binCode, String sku, BigDecimal min, BigDecimal max) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        BinService.BinRef bin = bins.byCode(cu, wh.id(), binCode.trim().toUpperCase(Locale.ROOT));
        if (!bins.labels(List.of(bin.id())).get(bin.id()).pickFace()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PICK_FACE", "Bin " + bin.code() + " is not a pick face; mark it on the Bins page first");
        }
        ProductService.ScanView p = products.lookup(sku.trim());
        long productId = products.basic(p.product().id()).id();
        checkMinMax(min, max);
        if (rules.existsByBinIdAndProductId(bin.id(), productId)) {
            throw new ApiException(HttpStatus.CONFLICT, "RULE_EXISTS", "There is already a rule for " + p.product().sku() + " in " + bin.code());
        }
        RefillRule r = rules.saveAndFlush(new RefillRule(wh.id(), bin.id(), productId, min, max, cu.userId()));
        audit.record(cu.tenantId(), cu.userId(), "REFILL_RULE_CREATED", "refill_rule", r.getPublicId(), null,
                Map.of("bin", bin.code(), "sku", p.product().sku(), "min", min, "max", max));
        return views(List.of(r), Map.of()).get(0);
    }

    @Transactional
    public RuleView update(CurrentUser cu, String publicId, int version, BigDecimal min, BigDecimal max, boolean active) {
        RefillRule r = visible(cu, rules.findByPublicId(publicId).orElse(null));
        if (r.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "RULE_CHANGED", "Someone else changed this rule; reload it");
        }
        checkMinMax(min, max);
        Map<String, Object> before = Map.of("min", r.getMinQty(), "max", r.getMaxQty(), "active", r.isActive());
        r.set(min, max, active);
        rules.flush();
        audit.record(cu.tenantId(), cu.userId(), "REFILL_RULE_UPDATED", "refill_rule", r.getPublicId(), before,
                Map.of("min", min, "max", max, "active", active));
        return views(List.of(r), Map.of()).get(0);
    }

    private static void checkMinMax(BigDecimal min, BigDecimal max) {
        if (min.signum() < 0 || max.compareTo(min) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MIN_MAX", "Max must be more than min");
        }
    }

    // ---------------------------------------------------------------- tasks

    @Transactional(readOnly = true)
    public Page<TaskView> openTasks(CurrentUser cu, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return tasks.open(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, pageable).map(this::view);
    }

    @Transactional(readOnly = true)
    public TaskView get(CurrentUser cu, String publicId) {
        return view(visibleTask(cu, tasks.findByPublicId(publicId).orElse(null)));
    }

    /**
     * Does the task: from the scanned bulk bin into the task's pick bin, a quantity up to what the task asks (in the
     * unit of the scanned code). Moving less than asked is allowed (less was there); the task is done either way.
     */
    @Transactional
    public TaskView confirm(CurrentUser cu, String publicId, String fromCode, String toCode, String code, BigDecimal qty) {
        RefillTask t = visibleTask(cu, tasks.lockByPublicId(publicId).orElse(null));
        if (t.getStatus() != RefillTask.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "REFILL_DONE", "This refill is already done or cancelled");
        }
        Map<Long, BinService.BinLabel> pick = bins.labels(List.of(t.getBinId()));
        if (!pick.get(t.getBinId()).code().equalsIgnoreCase(toCode.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_BIN", "Wrong pick bin: this refill goes to " + pick.get(t.getBinId()).code());
        }
        ProductService.ScanView scan = products.lookup(code.trim());
        ProductService.Basic product = products.basics(List.of(t.getProductId())).get(t.getProductId());
        if (product == null || !scan.product().sku().equals(product.sku())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_PRODUCT", "Wrong product: this refill is " + (product == null ? "?" : product.sku()));
        }
        BinService.BinRef from = bins.byCode(cu, t.getWarehouseId(), fromCode.trim().toUpperCase(Locale.ROOT));
        BinService.BinLabel fromLabel = bins.labels(List.of(from.id())).get(from.id());
        if (from.id() == t.getBinId() || fromLabel.pickFace() || fromLabel.quarantine()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_BULK_BIN", "Take it from a storage bin, not a pick face or quarantine");
        }
        BigDecimal base = qty.multiply(BigDecimal.valueOf(scan.factor())).setScale(3, RoundingMode.HALF_UP);
        if (base.compareTo(t.getQty()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "MORE_THAN_ASKED", "This refill asks for " + plain(t.getQty()) + " " + product.baseUom());
        }
        Stock lot = stock.lots(t.getWarehouseId(), from.id(), t.getProductId()).stream().filter(s -> s.available().signum() > 0)
                .min(fefo()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_STOCK", "No free " + product.sku() + " in " + from.code()));
        moves.move(new Move(Kind.TRANSFER, t.getWarehouseId(), t.getProductId(), lot.getBatchNo(), lot.getExpiryDate(), Place.bin(from.id()),
                Place.bin(t.getBinId()), base, "Refill " + t.getNumber(), REF, t.getId(), cu.userId()));
        t.done(base, cu.userId());
        tasks.flush();
        audit.record(cu.tenantId(), cu.userId(), "REFILL_DONE", "refill_task", t.getPublicId(), null,
                Map.of("number", t.getNumber(), "from", from.code(), "qty", base));
        return view(t);
    }

    @Transactional
    public TaskView cancel(CurrentUser cu, String publicId) {
        RefillTask t = visibleTask(cu, tasks.lockByPublicId(publicId).orElse(null));
        if (t.getStatus() != RefillTask.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "REFILL_DONE", "This refill is already done or cancelled");
        }
        t.cancel(cu.userId());
        tasks.flush();
        audit.record(cu.tenantId(), cu.userId(), "REFILL_CANCELLED", "refill_task", t.getPublicId(), null, Map.of("number", t.getNumber()));
        return view(t);
    }

    /** Open refills of a warehouse (the task board). */
    @Transactional(readOnly = true)
    public long countOpen(long warehouseId) {
        return tasks.countOpen(warehouseId);
    }

    // ---------------------------------------------------------------- helpers

    private static Comparator<Stock> fefo() {
        return Comparator.comparing(Stock::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Stock::getReceivedAt);
    }

    /** The best place to take from: oldest expiry, then oldest receipt, in a free bulk bin (not pick face, blocked, quarantine). */
    private Source suggest(RefillTask t) {
        List<Stock> free = stock.freeInBins(t.getWarehouseId(), t.getProductId());
        Map<Long, BinService.BinLabel> labels = bins.labels(free.stream().map(Stock::getBinId).distinct().toList());
        return free.stream().filter(s -> {
            BinService.BinLabel l = labels.get(s.getBinId());
            return l != null && !l.pickFace() && !l.blocked() && !l.quarantine() && !Objects.equals(s.getBinId(), t.getBinId());
        }).min(fefo()).map(s -> new Source(labels.get(s.getBinId()).code(), s.getBatchNo(), s.getExpiryDate(), s.available())).orElse(null);
    }

    private TaskView view(RefillTask t) {
        ProductService.Basic p = products.basics(List.of(t.getProductId())).get(t.getProductId());
        String bin = bins.labels(List.of(t.getBinId())).get(t.getBinId()).code();
        return new TaskView(t.getPublicId(), t.getNumber(), t.getStatus().name(), warehouses.codeOf(t.getWarehouseId()), bin,
                p == null ? "?" : p.sku(), p == null ? "" : p.name(), p == null ? "" : p.baseUom(), t.getQty(), t.getMovedQty(),
                t.getStatus() == RefillTask.Status.OPEN ? suggest(t) : null, t.getCreatedAt());
    }

    private List<RuleView> views(List<RefillRule> list, Map<Long, String> open) {
        Map<Long, BinService.BinLabel> labels = bins.labels(list.stream().map(RefillRule::getBinId).distinct().toList());
        Map<Long, ProductService.Basic> basics = products.basics(new HashSet<>(list.stream().map(RefillRule::getProductId).toList()));
        return list.stream().map(r -> {
            ProductService.Basic p = basics.get(r.getProductId());
            BinService.BinLabel b = labels.get(r.getBinId());
            return new RuleView(r.getPublicId(), b == null ? null : b.publicId(), b == null ? "?" : b.code(), p == null ? null : p.publicId(),
                    p == null ? "?" : p.sku(), p == null ? "" : p.name(), p == null ? "" : p.baseUom(), r.getMinQty(), r.getMaxQty(), r.isActive(),
                    onHand(r), open.get(r.getId()), r.getVersion());
        }).toList();
    }

    private RefillRule visible(CurrentUser cu, RefillRule r) {
        List<Long> ids = r == null ? null : warehouses.assignedIds(cu);
        if (r == null || (ids != null && !ids.contains(r.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Refill rule not found");
        }
        return r;
    }

    private RefillTask visibleTask(CurrentUser cu, RefillTask t) {
        List<Long> ids = t == null ? null : warehouses.assignedIds(cu);
        if (t == null || (ids != null && !ids.contains(t.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REFILL_NOT_FOUND", "Refill not found");
        }
        return t;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
