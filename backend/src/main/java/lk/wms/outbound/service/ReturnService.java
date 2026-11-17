package lk.wms.outbound.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.domain.PickLine;
import lk.wms.outbound.domain.ReturnLine;
import lk.wms.outbound.domain.ReturnOrder;
import lk.wms.outbound.domain.SalesOrder;
import lk.wms.outbound.repo.PickListRepository;
import lk.wms.outbound.repo.ReturnOrderRepository;
import lk.wms.outbound.repo.SalesOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer returns (v1.1 task 5). A return belongs to a shipped order and can take back at most what was shipped,
 * minus earlier returns. Nothing enters stock until a line is inspected: RESTOCK puts it into a storage bin,
 * QUARANTINE into a bin of a quarantine zone (never picked), SCRAP books nothing (the goods left on the shipment).
 */
@Service
public class ReturnService {

    public record LineSpec(String sku, String uom, BigDecimal qty, ReturnLine.Reason reason) {
    }

    /** What can still come back from a shipped order, per product, in base units. */
    public record Returnable(String sku, String productName, String baseUom, BigDecimal shipped, BigDecimal returned,
            BigDecimal left, Map<String, Integer> units) {
    }

    public record LineView(int seq, String sku, String productName, String baseUom, BigDecimal qty, String reason, String outcome,
            String binCode, String batchNo, LocalDate expiry, String inspectedBy, Instant inspectedAt) {
    }

    public record Detail(String id, String number, String orderId, String orderNumber, String customerName, String warehouseCode,
            String status, String note, String cancelReason, int version, Instant createdAt, Instant closedAt, int open,
            List<LineView> lines) {
    }

    public record Summary(String id, String number, String orderNumber, String customerName, String warehouseCode, String status,
            int lines, int open, Instant createdAt) {
    }

    static final String KIND = "RT";
    static final String REF = "RETURN";

    private final ReturnOrderRepository returns;
    private final SalesOrderRepository orders;
    private final PickListRepository lists;
    private final DocNumbers numbers;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final ProductService products;
    private final StockMovementService stock;
    private final AuditService audit;
    private final lk.wms.auth.service.UserService users;

    public ReturnService(ReturnOrderRepository returns, SalesOrderRepository orders, PickListRepository lists, DocNumbers numbers,
            WarehouseService warehouses, BinService bins, ProductService products, StockMovementService stock, AuditService audit,
            lk.wms.auth.service.UserService users) {
        this.returns = returns;
        this.orders = orders;
        this.lists = lists;
        this.numbers = numbers;
        this.warehouses = warehouses;
        this.bins = bins;
        this.products = products;
        this.stock = stock;
        this.audit = audit;
        this.users = users;
    }

    /** Per product of a shipped order: shipped, already returned and what is left to return. */
    @Transactional(readOnly = true)
    public List<Returnable> returnable(CurrentUser cu, String orderPublicId) {
        SalesOrder o = shippedOrder(cu, orderPublicId);
        Map<Long, BigDecimal[]> amounts = amounts(o); // product -> [shipped, returned]
        Map<Long, ProductService.Basic> basics = products.basics(amounts.keySet());
        Map<String, ProductService.OrderProduct> units = products.forOrder(basics.values().stream().map(ProductService.Basic::sku).toList());
        List<Returnable> out = new ArrayList<>();
        amounts.forEach((id, a) -> {
            ProductService.Basic b = basics.get(id);
            if (b != null) {
                ProductService.OrderProduct u = units.get(b.sku());
                out.add(new Returnable(b.sku(), b.name(), b.baseUom(), a[0], a[1], a[0].subtract(a[1]).max(BigDecimal.ZERO),
                        u == null ? Map.of(b.baseUom(), 1) : u.factors()));
            }
        });
        return out;
    }

    /** Records a return of a shipped order; nothing moves in stock yet. */
    @Transactional
    public Detail create(CurrentUser cu, String orderPublicId, String note, List<LineSpec> specs) {
        // two returns of one order at the same moment: the second waits for the first, then sees what it took back
        SalesOrder o = orders.lockByPublicIds(List.of(orderPublicId)).stream().findFirst().orElse(null);
        o = visibleShipped(cu, o);
        Map<Long, BigDecimal[]> amounts = amounts(o);
        Map<String, ProductService.OrderProduct> found = products.forOrder(specs.stream().map(LineSpec::sku).toList());
        Map<Long, BigDecimal> asked = new HashMap<>();
        ReturnOrder r = new ReturnOrder(numbers.next(cu, KIND), o.getId(), o.getWarehouseId(), blankToNull(note), cu.userId());
        Set<String> seen = new HashSet<>();
        for (LineSpec s : specs) {
            ProductService.OrderProduct p = found.get(s.sku());
            if (p == null || !amounts.containsKey(p.id())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_ON_ORDER", s.sku() + " was not shipped on this order");
            }
            Integer factor = p.factors().get(s.uom());
            if (factor == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UOM_NOT_FOUND",
                        s.sku() + " has no unit " + s.uom() + "; use one of " + String.join(", ", p.factors().keySet()));
            }
            if (!seen.add(s.sku() + "|" + s.reason())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_LINE", s.sku() + " with that reason is on the return twice");
            }
            BigDecimal base = s.qty().multiply(BigDecimal.valueOf(factor)).setScale(3, RoundingMode.HALF_UP);
            asked.merge(p.id(), base, BigDecimal::add);
            BigDecimal[] a = amounts.get(p.id());
            BigDecimal left = a[0].subtract(a[1]);
            if (asked.get(p.id()).compareTo(left) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "MORE_THAN_SHIPPED", "Only " + plain(left.max(BigDecimal.ZERO)) + " "
                        + p.baseUom() + " of " + p.sku() + " can still come back from " + o.getNumber());
            }
            r.add(new ReturnLine.Spec(p.id(), p.sku(), p.name(), p.baseUom(), base, s.reason()));
        }
        returns.saveAndFlush(r);
        audit.record(cu.tenantId(), cu.userId(), "RETURN_CREATED", "return_order", r.getPublicId(), null,
                Map.of("number", r.getNumber(), "order", o.getNumber(), "lines", r.getLines().size()));
        return detail(r, o);
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, List<ReturnOrder.Status> statuses, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        Page<ReturnOrder> page = returns.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, statuses, pageable);
        Map<Long, SalesOrder> byId = new HashMap<>();
        orders.byInternalIds(page.getContent().stream().map(ReturnOrder::getOrderId).distinct().toList()).forEach(o -> byId.put(o.getId(), o));
        return page.map(r -> {
            SalesOrder o = byId.get(r.getOrderId());
            return new Summary(r.getPublicId(), r.getNumber(), o.getNumber(), o.getCustomerName(), o.getWarehouseCode(), r.getStatus().name(),
                    r.getLines().size(), open(r), r.getCreatedAt());
        });
    }

    @Transactional(readOnly = true)
    public Detail get(CurrentUser cu, String publicId) {
        ReturnOrder r = visible(cu, returns.findByPublicId(publicId).orElse(null));
        return detail(r, order(r));
    }

    /**
     * Decides one line. RESTOCK needs a bin that is not in quarantine, QUARANTINE a bin in a quarantine zone; both
     * put the goods into that bin with a RETURN movement. SCRAP books nothing. Batch/expiry when the product tracks them.
     */
    @Transactional
    public Detail inspect(CurrentUser cu, String publicId, int seq, ReturnLine.Outcome outcome, String binCode, String batchNo,
            LocalDate expiry) {
        ReturnOrder r = visible(cu, returns.lockByPublicId(publicId).orElse(null));
        if (r.getStatus() != ReturnOrder.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "RETURN_CLOSED", "This return is closed");
        }
        ReturnLine line = r.getLines().stream().filter(l -> l.getSeq() == seq).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RETURN_LINE_NOT_FOUND", "No such line on this return"));
        if (line.getOutcome() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "RETURN_LINE_DONE", "This line is already inspected");
        }
        String batch = blankToNull(batchNo);
        if (outcome == ReturnLine.Outcome.SCRAP) {
            line.inspected(outcome, null, null, batch, expiry, cu.userId());
        } else {
            if (binCode == null || binCode.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BIN_REQUIRED", "Scan the bin the goods go into");
            }
            BinService.BinRef bin = bins.byCode(cu, r.getWarehouseId(), binCode.trim().toUpperCase(Locale.ROOT));
            boolean quarantine = bins.labels(List.of(bin.id())).get(bin.id()).quarantine();
            if (outcome == ReturnLine.Outcome.QUARANTINE && !quarantine) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_QUARANTINE_BIN", "Bin " + bin.code() + " is not in a quarantine zone");
            }
            if (outcome == ReturnLine.Outcome.RESTOCK && quarantine) {
                throw new ApiException(HttpStatus.CONFLICT, "QUARANTINE_BIN", "Bin " + bin.code() + " is in quarantine; choose Quarantine or another bin");
            }
            ProductService.ProductView p = products.lookup(line.getSku()).product();
            if (p.trackBatch() && batch == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BATCH_REQUIRED", "This product needs a batch number");
            }
            if (p.trackExpiry() && expiry == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EXPIRY_REQUIRED", "This product needs an expiry date");
            }
            stock.move(new Move(Kind.RETURN, r.getWarehouseId(), line.getProductId(), batch, expiry, null, Place.bin(bin.id()),
                    line.getQty(), "Return " + r.getNumber() + " (" + line.getReason().name().toLowerCase(Locale.ROOT) + ")", REF, r.getId(),
                    cu.userId()));
            line.inspected(outcome, bin.id(), bin.code(), batch, expiry, cu.userId());
        }
        r.progress();
        returns.flush();
        audit.record(cu.tenantId(), cu.userId(), "RETURN_INSPECTED", "return_order", r.getPublicId(), null,
                Map.of("line", seq, "sku", line.getSku(), "qty", line.getQty(), "outcome", outcome.name(),
                        "bin", line.getBinCode() == null ? "" : line.getBinCode()));
        return detail(r, order(r));
    }

    /** Only while nothing is inspected (after that, stock has already moved). */
    @Transactional
    public Detail cancel(CurrentUser cu, String publicId, int version, String reason) {
        ReturnOrder r = visible(cu, returns.lockByPublicId(publicId).orElse(null));
        if (r.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "RETURN_CHANGED", "Someone else changed this return; reload it");
        }
        if (r.getStatus() != ReturnOrder.Status.OPEN || r.getLines().stream().anyMatch(l -> l.getOutcome() != null)) {
            throw new ApiException(HttpStatus.CONFLICT, "RETURN_CANNOT_CANCEL", "Only a return with nothing inspected yet can be cancelled");
        }
        r.cancel(reason.trim());
        returns.flush();
        audit.record(cu.tenantId(), cu.userId(), "RETURN_CANCELLED", "return_order", r.getPublicId(), null,
                Map.of("number", r.getNumber(), "reason", r.getCancelReason()));
        return detail(r, order(r));
    }

    // ---------------------------------------------------------------- helpers

    /** [shipped, returned] per product: picked quantities of the order's pick list, and live earlier returns. */
    private Map<Long, BigDecimal[]> amounts(SalesOrder o) {
        Map<Long, BigDecimal[]> out = new LinkedHashMap<>();
        lists.findByOrderId(o.getId()).ifPresent(list -> {
            for (PickLine l : list.getLines()) {
                if (l.getQtyPicked().signum() > 0) {
                    BigDecimal[] a = out.computeIfAbsent(l.getProductId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    a[0] = a[0].add(l.getQtyPicked());
                }
            }
        });
        for (ReturnOrder earlier : returns.liveForOrder(o.getId())) {
            for (ReturnLine l : earlier.getLines()) {
                BigDecimal[] a = out.get(l.getProductId());
                if (a != null) {
                    a[1] = a[1].add(l.getQty());
                }
            }
        }
        return out;
    }

    private SalesOrder shippedOrder(CurrentUser cu, String orderPublicId) {
        return visibleShipped(cu, orders.findByPublicId(orderPublicId).orElse(null));
    }

    private SalesOrder visibleShipped(CurrentUser cu, SalesOrder o) {
        List<Long> ids = o == null ? null : warehouses.assignedIds(cu);
        if (o == null || (ids != null && !ids.contains(o.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SO_NOT_FOUND", "Sales order not found");
        }
        if (o.getStatus() != SalesOrder.Status.SHIPPED) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_NOT_SHIPPED", "Only a shipped order can have a return");
        }
        return o;
    }

    private ReturnOrder visible(CurrentUser cu, ReturnOrder r) {
        List<Long> ids = r == null ? null : warehouses.assignedIds(cu);
        if (r == null || (ids != null && !ids.contains(r.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RETURN_NOT_FOUND", "Return not found");
        }
        return r;
    }

    private SalesOrder order(ReturnOrder r) {
        return orders.byInternalIds(List.of(r.getOrderId())).get(0);
    }

    private static int open(ReturnOrder r) {
        return (int) r.getLines().stream().filter(l -> l.getOutcome() == null).count();
    }

    private Detail detail(ReturnOrder r, SalesOrder o) {
        Set<Long> people = new HashSet<>();
        r.getLines().forEach(l -> {
            if (l.getInspectedBy() != null) {
                people.add(l.getInspectedBy());
            }
        });
        Map<Long, lk.wms.auth.service.UserService.UserView> names = users.viewsByInternalId(people);
        List<LineView> lines = r.getLines().stream().map(l -> {
            var who = l.getInspectedBy() == null ? null : names.get(l.getInspectedBy());
            return new LineView(l.getSeq(), l.getSku(), l.getProductName(), l.getBaseUom(), l.getQty(), l.getReason().name(),
                    l.getOutcome() == null ? null : l.getOutcome().name(), l.getBinCode(), l.getBatchNo(), l.getExpiryDate(),
                    who == null ? null : who.fullName(), l.getInspectedAt());
        }).toList();
        return new Detail(r.getPublicId(), r.getNumber(), o.getPublicId(), o.getNumber(), o.getCustomerName(), o.getWarehouseCode(),
                r.getStatus().name(), r.getNote(), r.getCancelReason(), r.getVersion(), r.getCreatedAt(), r.getClosedAt(), open(r), lines);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
