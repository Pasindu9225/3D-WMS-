package lk.wms.outbound.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Allocation;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import lk.wms.inventory.service.StockMovementService.Reserved;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.domain.PickLine;
import lk.wms.outbound.domain.PickList;
import lk.wms.outbound.domain.SalesOrder;
import lk.wms.outbound.repo.PickListRepository;
import lk.wms.outbound.repo.SalesOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pick lists (v1.0 task 3). Releasing an order makes one, from its reservations, in bin order. A picker scans the
 * bin and the product and confirms a quantity; the stock moves from the bin to the dispatch area and stops being
 * reserved. A short pick closes the rest of a line and frees its reservation. Every change locks the list row.
 */
@Service
public class PickService {

    /** Product, unit and name of a sales order line, for the pick lines made from it. */
    record LineInfo(String sku, String productName, String baseUom) {
    }

    public record LineView(int seq, int soLineNo, String sku, String productName, String baseUom, String binCode,
            String batchNo, LocalDate expiryDate, BigDecimal qtyToPick, BigDecimal qtyPicked, String status, String shortReason) {
    }

    public record Detail(String id, String number, String status, String orderId, String orderNumber, String customerName,
            String warehouseCode, List<LineView> lines, int openLines) {
    }

    public record Summary(String id, String number, String status, String orderNumber, String customerName,
            String warehouseCode, int lines, int openLines) {
    }

    public enum ShortReason { NOT_THERE, DAMAGED }

    static final String KIND = "PL";

    private final PickListRepository lists;
    private final SalesOrderRepository orders;
    private final DocNumbers numbers;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final StockMovementService stock;
    private final AuditService audit;

    public PickService(PickListRepository lists, SalesOrderRepository orders, DocNumbers numbers, ProductService products,
            WarehouseService warehouses, StockMovementService stock, AuditService audit) {
        this.lists = lists;
        this.orders = orders;
        this.numbers = numbers;
        this.products = products;
        this.warehouses = warehouses;
        this.stock = stock;
        this.audit = audit;
    }

    /** Called by release: one line per reserved place, in bin-code order (the walk through the aisles). */
    PickList createFor(CurrentUser cu, SalesOrder o, List<Allocation> got, Map<Integer, LineInfo> info) {
        PickList list = new PickList(numbers.next(cu, KIND), o.getId(), o.getWarehouseId());
        got.stream().sorted(Comparator.comparing(Allocation::binCode).thenComparing(Allocation::line)).forEach(a -> {
            LineInfo i = info.get(a.line());
            list.add(new PickLine.Spec(a.line(), a.productId(), i.sku(), i.productName(), i.baseUom(), a.binId(), a.binCode(),
                    a.batchNo(), a.expiry(), a.qty()));
        });
        return lists.save(list);
    }

    /** Called by cancel of a released order: the list goes, as long as nothing was picked from it. */
    void dropFor(SalesOrder o) {
        lists.findByOrderId(o.getId()).ifPresent(p -> {
            PickList locked = lists.lockByPublicId(p.getPublicId()).orElseThrow();
            if (locked.getStatus() != PickList.Status.OPEN) {
                throw new ApiException(HttpStatus.CONFLICT, "SO_CANNOT_CANCEL", "Picking has started on this order");
            }
            lists.delete(locked);
        });
    }

    /** The order's pick list, if it has one. */
    Detail forOrder(SalesOrder o) {
        return lists.findByOrderId(o.getId()).map(p -> detail(p, o)).orElse(null);
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, List<PickList.Status> statuses, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        Page<PickList> page = lists.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, statuses, pageable);
        Map<Long, SalesOrder> byId = orders.byInternalIds(page.getContent().stream().map(PickList::getOrderId).toList())
                .stream().collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
        return page.map(p -> {
            SalesOrder o = byId.get(p.getOrderId());
            return new Summary(p.getPublicId(), p.getNumber(), p.getStatus().name(), o.getNumber(), o.getCustomerName(),
                    o.getWarehouseCode(), p.getLines().size(), open(p));
        });
    }

    @Transactional(readOnly = true)
    public Detail get(CurrentUser cu, String publicId) {
        PickList p = lists.findByPublicId(publicId).orElse(null);
        access(cu, p);
        return detail(p, order(p));
    }

    /**
     * Picks some or all of a line: the scanned bin and product must be the line's; the quantity is in the unit of
     * the scanned code (a case barcode counts cases). A retried request (same Idempotency-Key) changes nothing.
     */
    @Transactional
    public Detail confirm(CurrentUser cu, String publicId, int seq, String binCode, String code, BigDecimal qty, String clientRef) {
        PickList p = lists.lockByPublicId(publicId).orElse(null);
        access(cu, p);
        SalesOrder o = order(p);
        if (clientRef != null && stock.isDone(clientRef)) {
            return detail(p, o);
        }
        PickLine line = openLine(p, seq);
        if (!line.getBinCode().equalsIgnoreCase(binCode.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_BIN", "Wrong bin: this line is in " + line.getBinCode());
        }
        ProductService.ScanView scan = products.lookup(code.trim());
        if (!scan.product().sku().equals(line.getSku())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_PRODUCT", "Wrong product: this line is " + line.getSku());
        }
        BigDecimal base = qty.multiply(BigDecimal.valueOf(scan.factor())).setScale(3, RoundingMode.HALF_UP);
        if (base.compareTo(line.left()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "OVER_PICK", "Only " + plain(line.left()) + " " + line.getBaseUom() + " left on this line");
        }
        stock.move(new Move(Kind.PICK, p.getWarehouseId(), line.getProductId(), line.getBatchNo(), line.getExpiryDate(),
                Place.bin(line.getBinId()), Place.DISPATCH, base, null, KIND, p.getId(), cu.userId(), clientRef,
                new Reserved(SalesOrderService.REF, o.getId(), line.getSoLineNo())));
        line.picked(base);
        progress(cu, p, o);
        audit.record(cu.tenantId(), cu.userId(), "PICK_CONFIRMED", "pick_list", p.getPublicId(), null,
                Map.of("line", seq, "sku", line.getSku(), "bin", line.getBinCode(), "qty", base));
        return detail(p, o);
    }

    /** Closes what is left of a line as not found: its reservation is freed and the manager sees it in the audit log. */
    @Transactional
    public Detail shortPick(CurrentUser cu, String publicId, int seq, ShortReason reason) {
        PickList p = lists.lockByPublicId(publicId).orElse(null);
        access(cu, p);
        SalesOrder o = order(p);
        PickLine line = openLine(p, seq);
        BigDecimal missing = line.left();
        stock.unreservePlace(SalesOrderService.REF, o.getId(), line.getSoLineNo(), line.getBinId(), line.getBatchNo(), line.getExpiryDate());
        line.shorted(reason.name());
        progress(cu, p, o);
        audit.record(cu.tenantId(), cu.userId(), "PICK_SHORT", "pick_list", p.getPublicId(), null,
                Map.of("line", seq, "sku", line.getSku(), "bin", line.getBinCode(), "missing", missing, "reason", reason.name()));
        return detail(p, o);
    }

    // ---------------------------------------------------------------- helpers

    private void progress(CurrentUser cu, PickList p, SalesOrder o) {
        p.progress(cu.userId());
        o.picking(p.getStatus() == PickList.Status.DONE);
        lists.flush();
        orders.flush();
    }

    private static PickLine openLine(PickList p, int seq) {
        PickLine line = p.getLines().stream().filter(l -> l.getSeq() == seq).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PICK_LINE_NOT_FOUND", "No such line on this pick list"));
        if (line.getStatus() != PickLine.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "PICK_LINE_DONE", "This line is already done");
        }
        return line;
    }

    /** The list if this user may work in its warehouse; otherwise it does not exist for them. */
    private void access(CurrentUser cu, PickList p) {
        List<Long> ids = p == null ? null : warehouses.assignedIds(cu);
        if (p == null || (ids != null && !ids.contains(p.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PICK_LIST_NOT_FOUND", "Pick list not found");
        }
    }

    private SalesOrder order(PickList p) {
        return orders.byInternalIds(List.of(p.getOrderId())).get(0);
    }

    private static int open(PickList p) {
        return (int) p.getLines().stream().filter(l -> l.getStatus() == PickLine.Status.OPEN).count();
    }

    Detail detail(PickList p, SalesOrder o) {
        List<LineView> lines = p.getLines().stream().map(l -> new LineView(l.getSeq(), l.getSoLineNo(), l.getSku(),
                l.getProductName(), l.getBaseUom(), l.getBinCode(), l.getBatchNo(), l.getExpiryDate(), l.getQtyToPick(),
                l.getQtyPicked(), l.getStatus().name(), l.getShortReason())).toList();
        return new Detail(p.getPublicId(), p.getNumber(), p.getStatus().name(), o.getPublicId(), o.getNumber(), o.getCustomerName(),
                o.getWarehouseCode(), lines, open(p));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().toUpperCase(Locale.ROOT);
    }
}
