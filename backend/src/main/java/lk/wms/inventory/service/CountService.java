package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.domain.CountLine;
import lk.wms.inventory.domain.CountPlan;
import lk.wms.inventory.domain.Stock;
import lk.wms.inventory.repo.CountPlanRepository;
import lk.wms.inventory.repo.StockRepository;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle counts (v1.1 task 3). A manager starts a plan for a warehouse, a zone or an ABC class: one line per bin,
 * product and lot that holds stock there, in bin order. Counters count blind on the phone; the expected quantity
 * is read under the stock row lock at the moment a line is counted, so picking and moving go on during a count.
 * Once every line is counted, the manager sees the differences and approves them (COUNT movements) or sends lines
 * back for a recount. Every change locks the plan row.
 */
@Service
public class CountService {

    /** What a counter sees: never the expected quantity. */
    public record CounterLine(int seq, String binCode, String sku, String productName, String baseUom, String batchNo,
            LocalDate expiry, boolean added, String status, BigDecimal counted) {
    }

    public record CounterView(String id, String number, String warehouseCode, String status, int lines, int open,
            List<CounterLine> items) {
    }

    /** What the manager sees when reviewing: expected, counted, difference and its value (cents). */
    public record ReviewLine(int seq, String binCode, String sku, String productName, String baseUom, String batchNo,
            LocalDate expiry, boolean added, String status, BigDecimal expected, BigDecimal counted, BigDecimal difference,
            Long valueMinor, String countedBy, Instant countedAt) {
    }

    public record ReviewView(String id, String number, String warehouseCode, String scope, String scopeValue,
            String status, Instant createdAt, Instant approvedAt, String cancelReason, int version, int differences,
            long valueMinor, List<ReviewLine> lines) {
    }

    public record Summary(String id, String number, String warehouseCode, String scope, String scopeValue, String status,
            int lines, int counted, Instant createdAt) {
    }

    static final String KIND = "CC";
    static final String REF = "COUNT";
    static final String APPROVE = "counts:approve";

    private final CountPlanRepository plans;
    private final StockRepository stock;
    private final StockMovementService moves;
    private final DocNumbers numbers;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final ProductService products;
    private final AuditService audit;
    private final lk.wms.auth.service.UserService users;
    private final int maxLines;

    public CountService(CountPlanRepository plans, StockRepository stock, StockMovementService moves, DocNumbers numbers,
            WarehouseService warehouses, BinService bins, ProductService products, AuditService audit,
            lk.wms.auth.service.UserService users,
            @org.springframework.beans.factory.annotation.Value("${wms.count.max-lines:2000}") int maxLines) {
        this.maxLines = maxLines;
        this.plans = plans;
        this.stock = stock;
        this.moves = moves;
        this.numbers = numbers;
        this.warehouses = warehouses;
        this.bins = bins;
        this.products = products;
        this.audit = audit;
        this.users = users;
    }

    /** Starts a count of a whole warehouse, one zone (bins whose code starts with it) or one ABC class. */
    @Transactional
    public ReviewView create(CurrentUser cu, String warehouseId, CountPlan.Scope scope, String scopeValue) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        String value = scopeValue == null ? null : scopeValue.trim().toUpperCase(Locale.ROOT);
        List<Long> binIds = List.of(-1L);
        List<Long> productIds = List.of(-1L);
        if (scope == CountPlan.Scope.ZONE) {
            if (value == null || value.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ZONE_REQUIRED", "Choose the zone to count");
            }
            binIds = bins.idsByCodePrefix(cu, wh.id(), value + "-");
        } else if (scope == CountPlan.Scope.ABC) {
            if (value == null || !List.of("A", "B", "C").contains(value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ABC_REQUIRED", "Choose class A, B or C");
            }
            productIds = products.idsWithAbc(value);
        } else {
            value = null;
        }
        List<Stock> rows = scope == CountPlan.Scope.ZONE && binIds.isEmpty() || scope == CountPlan.Scope.ABC && productIds.isEmpty()
                ? List.of()
                : stock.inBins(wh.id(), scope != CountPlan.Scope.ZONE, binIds.isEmpty() ? List.of(-1L) : binIds,
                        scope != CountPlan.Scope.ABC, productIds.isEmpty() ? List.of(-1L) : productIds,
                        org.springframework.data.domain.PageRequest.of(0, maxLines + 1)); // never more rows than the limit needs
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTHING_TO_COUNT", "There is no stock in bins to count there");
        }
        if (rows.size() > maxLines) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COUNT_TOO_LARGE",
                    "That is more than " + maxLines + " places; count at most " + maxLines + " at a time (choose a zone)");
        }
        Map<Long, BinService.BinLabel> labels = bins.labels(rows.stream().map(Stock::getBinId).distinct().toList());
        Map<Long, ProductService.Basic> basics = products.basics(new HashSet<>(rows.stream().map(Stock::getProductId).toList()));
        CountPlan plan = new CountPlan(numbers.next(cu, KIND), wh.id(), scope, value, cu.userId());
        rows.stream().filter(s -> basics.containsKey(s.getProductId()))
                .sorted(Comparator.comparing((Stock s) -> labels.get(s.getBinId()).code())
                        .thenComparing(s -> basics.get(s.getProductId()).sku())
                        .thenComparing(Stock::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(s -> Objects.toString(s.getBatchNo(), "")))
                .forEach(s -> plan.add(spec(s.getBinId(), labels.get(s.getBinId()).code(), basics.get(s.getProductId()),
                        s.getBatchNo(), s.getExpiryDate()), false));
        plans.saveAndFlush(plan);
        audit.record(cu.tenantId(), cu.userId(), "COUNT_CREATED", "count_plan", plan.getPublicId(), null,
                Map.of("number", plan.getNumber(), "scope", scope.name() + (value == null ? "" : " " + value), "lines", plan.getLines().size()));
        return review(plan);
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, List<CountPlan.Status> statuses, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return plans.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, statuses, pageable)
                .map(p -> new Summary(p.getPublicId(), p.getNumber(), warehouses.codeOf(p.getWarehouseId()), p.getScope().name(),
                        p.getScopeValue(), p.getStatus().name(), p.getLines().size(),
                        (int) p.getLines().stream().filter(l -> l.getStatus() == CountLine.Status.COUNTED).count(), p.getCreatedAt()));
    }

    /** The counter's view: places to count and what they counted, never the expected quantity. */
    @Transactional(readOnly = true)
    public CounterView forCounter(CurrentUser cu, String publicId) {
        return counter(find(cu, publicId));
    }

    /** The manager's view with expected quantities and differences. */
    @Transactional(readOnly = true)
    public ReviewView forReview(CurrentUser cu, String publicId) {
        return review(find(cu, publicId));
    }

    /**
     * Counts one line: the scanned bin and product must be the line's; the quantity is in the unit of the scanned
     * code. The expected quantity is what the place holds at this moment. A retry of the same count changes nothing.
     */
    @Transactional
    public CounterView count(CurrentUser cu, String publicId, int seq, String binCode, String code, BigDecimal qty) {
        CountPlan plan = visible(cu, plans.lockByPublicId(publicId).orElse(null)); // open is checked below, after a retry
        CountLine line = plan.getLines().stream().filter(l -> l.getSeq() == seq).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COUNT_LINE_NOT_FOUND", "No such line on this count"));
        if (!line.getBinCode().equalsIgnoreCase(binCode.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_BIN", "Wrong bin: this line is in " + line.getBinCode());
        }
        ProductService.ScanView scan = products.lookup(code.trim());
        if (!scan.product().sku().equals(line.getSku())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_PRODUCT", "Wrong product: this line is " + line.getSku());
        }
        BigDecimal base = qty.multiply(BigDecimal.valueOf(scan.factor())).setScale(3, RoundingMode.HALF_UP);
        if (line.getStatus() == CountLine.Status.COUNTED) {
            if (Objects.equals(line.getCountedBy(), cu.userId()) && line.getCountedQty().compareTo(base) == 0) {
                return counter(plan); // the phone sent the same count again
            }
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_LINE_DONE", "This line is already counted");
        }
        if (plan.getStatus() != CountPlan.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_NOT_OPEN", "This count is not open for counting");
        }
        save(cu, plan, line, base);
        return counter(plan);
    }

    /** Goods found in a bin that are not on the count: a new line, counted at once (expected = what the system has). */
    @Transactional
    public CounterView addFound(CurrentUser cu, String publicId, String binCode, String code, BigDecimal qty, String batchNo,
            LocalDate expiry) {
        CountPlan plan = lockOpen(cu, publicId);
        BinService.BinRef bin = bins.byCode(cu, plan.getWarehouseId(), binCode.trim().toUpperCase(Locale.ROOT));
        ProductService.ScanView scan = products.lookup(code.trim());
        String batch = batchNo == null || batchNo.isBlank() ? null : batchNo.trim();
        boolean listed = plan.getLines().stream().anyMatch(l -> l.getBinId() == bin.id() && l.getSku().equals(scan.product().sku())
                && Objects.equals(l.getBatchNo(), batch) && Objects.equals(l.getExpiryDate(), expiry));
        if (listed) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_LINE_LISTED", "This product is already on the count for that bin");
        }
        if (plan.getLines().size() >= maxLines) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COUNT_TOO_LARGE", "This count already has " + maxLines + " lines");
        }
        ProductService.Basic basic = products.basic(scan.product().id());
        CountLine line = plan.add(spec(bin.id(), bin.code(), basic, batch, expiry), true);
        BigDecimal base = qty.multiply(BigDecimal.valueOf(scan.factor())).setScale(3, RoundingMode.HALF_UP);
        save(cu, plan, line, base);
        return counter(plan);
    }

    /** Sends lines back to the counters (their figures are dropped). */
    @Transactional
    public ReviewView recount(CurrentUser cu, String publicId, int version, List<Integer> seqs) {
        CountPlan plan = lockCurrent(cu, publicId, version);
        if (plan.getStatus() != CountPlan.Status.OPEN && plan.getStatus() != CountPlan.Status.REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_CLOSED", "This count is closed");
        }
        Set<Integer> wanted = new HashSet<>(seqs);
        List<CountLine> chosen = plan.getLines().stream().filter(l -> wanted.contains(l.getSeq())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COUNT_LINE_NOT_FOUND", "No such line on this count");
        }
        chosen.forEach(CountLine::recount);
        plan.progress();
        plans.flush();
        audit.record(cu.tenantId(), cu.userId(), "COUNT_RECOUNT", "count_plan", plan.getPublicId(), null,
                Map.of("number", plan.getNumber(), "lines", chosen.stream().map(l -> l.getBinCode() + " " + l.getSku()).toList()));
        return review(plan);
    }

    /**
     * Books every difference as a COUNT movement (found = into the bin, missing = out of it) and closes the count.
     * All or nothing: if one difference cannot be booked (the missing units are reserved), nothing is.
     */
    @Transactional
    public ReviewView approve(CurrentUser cu, String publicId, int version) {
        CountPlan plan = lockCurrent(cu, publicId, version);
        if (plan.getStatus() != CountPlan.Status.REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_NOT_READY", "Every line must be counted first");
        }
        List<Map<String, Object>> booked = new ArrayList<>();
        for (CountLine l : plan.getLines()) {
            BigDecimal diff = l.difference();
            if (diff.signum() == 0) {
                continue;
            }
            Place bin = Place.bin(l.getBinId());
            try {
                moves.move(new Move(Kind.COUNT, plan.getWarehouseId(), l.getProductId(), l.getBatchNo(), l.getExpiryDate(),
                        diff.signum() < 0 ? bin : null, diff.signum() > 0 ? bin : null, diff.abs(), "Count " + plan.getNumber(),
                        REF, plan.getId(), cu.userId()));
            } catch (ApiException e) {
                throw new ApiException(e.getStatus(), e.getCode(), l.getBinCode() + " " + l.getSku() + ": " + e.getMessage());
            }
            booked.add(Map.of("bin", l.getBinCode(), "sku", l.getSku(), "difference", diff));
        }
        plan.approve(cu.userId());
        plans.flush();
        audit.record(cu.tenantId(), cu.userId(), "COUNT_APPROVED", "count_plan", plan.getPublicId(), null,
                Map.of("number", plan.getNumber(), "differences", booked));
        return review(plan);
    }

    @Transactional
    public ReviewView cancel(CurrentUser cu, String publicId, int version, String reason) {
        CountPlan plan = lockCurrent(cu, publicId, version);
        if (plan.getStatus() != CountPlan.Status.OPEN && plan.getStatus() != CountPlan.Status.REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_CLOSED", "This count is closed");
        }
        plan.cancel(reason.trim());
        plans.flush();
        audit.record(cu.tenantId(), cu.userId(), "COUNT_CANCELLED", "count_plan", plan.getPublicId(), null,
                Map.of("number", plan.getNumber(), "reason", plan.getCancelReason()));
        return review(plan);
    }

    // ---------------------------------------------------------------- helpers

    private void save(CurrentUser cu, CountPlan plan, CountLine line, BigDecimal counted) {
        BigDecimal expected = moves.onHandLocked(plan.getWarehouseId(), line.getBinId(), line.getProductId(), line.getBatchNo(),
                line.getExpiryDate());
        line.counted(counted, expected, cu.userId());
        plan.progress();
        plans.flush();
        audit.record(cu.tenantId(), cu.userId(), "COUNT_LINE", "count_plan", plan.getPublicId(), null,
                Map.of("bin", line.getBinCode(), "sku", line.getSku(), "counted", counted, "added", line.isAdded()));
    }

    private static CountLine.Spec spec(long binId, String binCode, ProductService.Basic p, String batchNo, LocalDate expiry) {
        return new CountLine.Spec(binId, binCode, p.id(), p.sku(), p.name(), p.baseUom(), batchNo, expiry);
    }

    /** The plan if this user may work in its warehouse; otherwise it does not exist for them. */
    private CountPlan find(CurrentUser cu, String publicId) {
        return visible(cu, plans.findByPublicId(publicId).orElse(null));
    }

    private CountPlan visible(CurrentUser cu, CountPlan p) {
        List<Long> ids = p == null ? null : warehouses.assignedIds(cu);
        if (p == null || (ids != null && !ids.contains(p.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COUNT_NOT_FOUND", "Count not found");
        }
        return p;
    }

    private CountPlan lockOpen(CurrentUser cu, String publicId) {
        CountPlan plan = visible(cu, plans.lockByPublicId(publicId).orElse(null));
        if (plan.getStatus() != CountPlan.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_NOT_OPEN", "This count is not open for counting");
        }
        return plan;
    }

    private CountPlan lockCurrent(CurrentUser cu, String publicId, int version) {
        CountPlan plan = visible(cu, plans.lockByPublicId(publicId).orElse(null));
        if (plan.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNT_CHANGED", "Someone else changed this count; reload it");
        }
        return plan;
    }

    private CounterView counter(CountPlan p) {
        List<CounterLine> items = p.getLines().stream().map(l -> new CounterLine(l.getSeq(), l.getBinCode(), l.getSku(),
                l.getProductName(), l.getBaseUom(), l.getBatchNo(), l.getExpiryDate(), l.isAdded(), l.getStatus().name(),
                l.getCountedQty())).toList();
        int open = (int) p.getLines().stream().filter(l -> l.getStatus() == CountLine.Status.OPEN).count();
        return new CounterView(p.getPublicId(), p.getNumber(), warehouses.codeOf(p.getWarehouseId()), p.getStatus().name(),
                items.size(), open, items);
    }

    private ReviewView review(CountPlan p) {
        Map<Long, ProductService.Basic> basics = products.basics(new HashSet<>(p.getLines().stream().map(CountLine::getProductId).toList()));
        Set<Long> people = new HashSet<>();
        p.getLines().forEach(l -> {
            if (l.getCountedBy() != null) {
                people.add(l.getCountedBy());
            }
        });
        Map<Long, lk.wms.auth.service.UserService.UserView> names = users.viewsByInternalId(people);
        Map<String, Long> totals = new HashMap<>(Map.of("n", 0L, "value", 0L));
        List<ReviewLine> lines = p.getLines().stream().map(l -> {
            BigDecimal diff = l.getStatus() == CountLine.Status.COUNTED ? l.difference() : null;
            ProductService.Basic b = basics.get(l.getProductId());
            Long value = diff == null || b == null || b.costMinor() == null ? null
                    : diff.multiply(BigDecimal.valueOf(b.costMinor())).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (diff != null && diff.signum() != 0) {
                totals.merge("n", 1L, Long::sum);
                totals.merge("value", value == null ? 0L : value, Long::sum);
            }
            var who = l.getCountedBy() == null ? null : names.get(l.getCountedBy());
            return new ReviewLine(l.getSeq(), l.getBinCode(), l.getSku(), l.getProductName(), l.getBaseUom(), l.getBatchNo(),
                    l.getExpiryDate(), l.isAdded(), l.getStatus().name(), l.getExpectedQty(), l.getCountedQty(), diff, value,
                    who == null ? null : who.fullName(), l.getCountedAt());
        }).toList();
        return new ReviewView(p.getPublicId(), p.getNumber(), warehouses.codeOf(p.getWarehouseId()), p.getScope().name(), p.getScopeValue(),
                p.getStatus().name(), p.getCreatedAt(), p.getApprovedAt(), p.getCancelReason(), p.getVersion(),
                totals.get("n").intValue(), totals.get("value"), lines);
    }
}
