package lk.wms.inbound.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lk.wms.catalog.service.ProductService;
import lk.wms.catalog.service.ProductService.ScanView;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.domain.PoLine;
import lk.wms.inbound.domain.PurchaseOrder;
import lk.wms.inbound.domain.Receipt;
import lk.wms.inbound.domain.ReceiptLine;
import lk.wms.inbound.repo.PurchaseOrderRepository;
import lk.wms.inbound.repo.ReceiptRepository;
import lk.wms.layout.service.WarehouseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receiving on a phone (v0.4 task 4). A receiver opens a receipt (GRN) for an approved order, scans products
 * (barcode or SKU) with a quantity and any damage, and closes it; closing adds the quantities to the order lines
 * and moves the order to PARTLY_RECEIVED or RECEIVED. One receipt per order is open at a time, and every scan
 * locks it, so two phones on one delivery can never receive more than was ordered. A retried scan (same
 * Idempotency-Key) is counted once. Stock is posted from closed receipts in task 6.
 */
@Service
public class ReceiptService {

    public record ScanSpec(String code, String uom, BigDecimal qty, BigDecimal damagedQty, String damageNote,
            String clientRef) {
    }

    public record ReceiptSummary(String id, String number, String status, Instant createdAt, Instant closedAt) {
    }

    /** One order line with what is still expected: all quantities in the product's base unit. */
    public record LineProgress(int lineNo, String sku, String productName, String baseUom, BigDecimal ordered,
            BigDecimal receivedBefore, BigDecimal thisReceipt, BigDecimal damagedThisReceipt) {
    }

    public record Scan(String id, String sku, String uom, int factor, BigDecimal qty, BigDecimal qtyBase,
            BigDecimal damagedBase, String damageNote, Instant createdAt) {
    }

    public record ReceiptDetail(String id, String number, String status, String poId, String poNumber,
            String supplierName, String warehouseCode, String note, Instant createdAt, Instant closedAt,
            List<LineProgress> lines, List<Scan> scans, int version) {
    }

    static final String KIND = "GRN";

    private final ReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final DocNumbers numbers;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final AuditService audit;

    public ReceiptService(ReceiptRepository receipts, PurchaseOrderRepository orders, DocNumbers numbers,
            ProductService products, WarehouseService warehouses, AuditService audit) {
        this.receipts = receipts;
        this.orders = orders;
        this.numbers = numbers;
        this.products = products;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ReceiptSummary> forOrder(CurrentUser cu, String poId) {
        PurchaseOrder o = order(cu, poId, false);
        return receipts.findTop50ByOrderOrderByIdDesc(o).stream()
                .map(r -> new ReceiptSummary(r.getPublicId(), r.getNumber(), r.getStatus().name(), r.getCreatedAt(),
                        r.getClosedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReceiptDetail get(CurrentUser cu, String receiptId) {
        Receipt r = receipts.findByPublicId(receiptId).orElse(null);
        return detail(visible(cu, r));
    }

    /** The order's open receipt, or a new one (GRN-000001 ...). The order row is locked meanwhile. */
    @Transactional
    public ReceiptDetail open(CurrentUser cu, String poId) {
        PurchaseOrder o = order(cu, poId, true);
        if (o.getStatus() != PurchaseOrder.Status.APPROVED && o.getStatus() != PurchaseOrder.Status.PARTLY_RECEIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "PO_NOT_RECEIVABLE", "Only an approved order can be received");
        }
        Receipt r = receipts.findFirstByOrderAndStatus(o, Receipt.Status.OPEN).orElse(null);
        if (r == null) {
            r = receipts.saveAndFlush(new Receipt(numbers.next(cu, KIND), o, cu.userId()));
            audit.record(cu.tenantId(), cu.userId(), "RECEIPT_OPENED", "receipt", r.getPublicId(), null,
                    Map.of("number", r.getNumber(), "po", o.getNumber()));
        }
        return detail(r);
    }

    @Transactional
    public ReceiptDetail scan(CurrentUser cu, String receiptId, ScanSpec s) {
        Receipt r = openForChange(cu, receiptId);
        if (r.getLines().stream().anyMatch(l -> l.getClientRef().equals(s.clientRef()))) {
            return detail(r); // the phone retried a scan that already counted
        }
        ScanView found = products.lookup(s.code().trim());
        String uom = s.uom() == null ? found.uom() : s.uom();
        int factor = factorOf(found, uom);
        List<PoLine> candidates = r.getOrder().getLines().stream()
                .filter(l -> l.getSku().equals(found.product().sku())).toList();
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_ON_ORDER",
                    found.product().sku() + " is not on " + r.getOrder().getNumber());
        }
        BigDecimal qtyBase = s.qty().multiply(BigDecimal.valueOf(factor)).setScale(3, RoundingMode.UNNECESSARY);
        BigDecimal damaged = s.damagedQty() == null ? BigDecimal.ZERO
                : s.damagedQty().multiply(BigDecimal.valueOf(factor)).setScale(3, RoundingMode.UNNECESSARY);
        if (damaged.compareTo(qtyBase) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DAMAGED_TOO_MANY", "Damaged cannot be more than received");
        }
        // The line ordered in this unit first, then any line of the product with enough still expected.
        PoLine line = candidates.stream()
                .sorted(Comparator.comparing((PoLine l) -> !l.getUom().equals(uom)))
                .filter(l -> remaining(r, l).compareTo(qtyBase) >= 0)
                .findFirst().orElse(null);
        if (line == null) {
            BigDecimal left = candidates.stream().map(l -> remaining(r, l)).reduce(BigDecimal.ZERO, BigDecimal::add);
            throw new ApiException(HttpStatus.CONFLICT, "OVER_RECEIPT", "Only " + left.stripTrailingZeros().toPlainString()
                    + " " + baseUomOf(found) + " of " + found.product().sku() + " are still expected on this order");
        }
        String note = s.damageNote() == null || s.damageNote().isBlank() ? null : s.damageNote().trim();
        ReceiptLine added = r.add(new ReceiptLine.Spec(line, uom, factor, s.qty().setScale(3, RoundingMode.UNNECESSARY),
                qtyBase, damaged, damaged.signum() > 0 ? note : null, s.clientRef()), cu.userId());
        receipts.flush();
        audit.record(cu.tenantId(), cu.userId(), "RECEIPT_SCANNED", "receipt", r.getPublicId(), null,
                Map.of("line", added.getPublicId(), "sku", line.getSku(), "qtyBase", qtyBase, "damagedBase", damaged));
        return detail(r);
    }

    /** Takes back a mistaken scan while the receipt is open. */
    @Transactional
    public ReceiptDetail removeScan(CurrentUser cu, String receiptId, String lineId) {
        Receipt r = openForChange(cu, receiptId);
        ReceiptLine line = r.getLines().stream().filter(l -> l.getPublicId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCAN_NOT_FOUND", "Scan not found"));
        r.getLines().remove(line);
        receipts.flush();
        audit.record(cu.tenantId(), cu.userId(), "RECEIPT_SCAN_REMOVED", "receipt", r.getPublicId(),
                Map.of("line", lineId, "sku", line.getPoLine().getSku(), "qtyBase", line.getQtyBase()), null);
        return detail(r);
    }

    /** Closes the receipt: its quantities are added to the order lines and the order status follows. */
    @Transactional
    public ReceiptDetail close(CurrentUser cu, String receiptId, int version, String note) {
        Receipt r = openForChange(cu, receiptId);
        if (r.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_CHANGED", "This receipt changed; reload it");
        }
        if (r.getLines().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECEIPT_EMPTY", "Scan at least one product before closing");
        }
        PurchaseOrder o = r.getOrder();
        r.getLines().forEach(l -> l.getPoLine().addReceived(l.getQtyBase()));
        o.updateReceivedStatus();
        r.close(cu.userId(), note == null || note.isBlank() ? null : note.trim());
        receipts.flush();
        Map<String, BigDecimal> bySku = r.getLines().stream().collect(Collectors.groupingBy(l -> l.getPoLine().getSku(),
                java.util.TreeMap::new, Collectors.reducing(BigDecimal.ZERO, ReceiptLine::getQtyBase, BigDecimal::add)));
        audit.record(cu.tenantId(), cu.userId(), "RECEIPT_CLOSED", "receipt", r.getPublicId(), null,
                Map.of("number", r.getNumber(), "po", o.getNumber(), "poStatus", o.getStatus().name(), "received", bySku));
        return detail(r);
    }

    // ---------------------------------------------------------------- helpers

    private static int factorOf(ScanView found, String uom) {
        if (uom.equals(found.uom())) {
            return found.factor();
        }
        if (uom.equals(found.product().baseUom())) {
            return 1;
        }
        return found.product().units().stream().filter(u -> u.uom().equals(uom)).findFirst()
                .map(ProductService.UnitView::factor)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UOM_NOT_FOUND",
                        found.product().sku() + " has no unit " + uom));
    }

    private static String baseUomOf(ScanView found) {
        return found.product().baseUom();
    }

    /** Still expected on an order line: ordered minus closed receipts minus this receipt's scans (base units). */
    private static BigDecimal remaining(Receipt r, PoLine l) {
        BigDecimal here = r.getLines().stream().filter(x -> x.getPoLine() == l).map(ReceiptLine::getQtyBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return l.orderedBase().subtract(l.getQtyReceived()).subtract(here);
    }

    private PurchaseOrder order(CurrentUser cu, String poId, boolean lock) {
        PurchaseOrder o = (lock ? orders.lockByPublicId(poId) : orders.findByPublicId(poId)).orElse(null);
        if (o == null || !canSee(cu, o.getWarehouseId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PO_NOT_FOUND", "Purchase order not found");
        }
        return o;
    }

    private Receipt openForChange(CurrentUser cu, String receiptId) {
        Receipt r = visible(cu, receipts.lockByPublicId(receiptId).orElse(null));
        if (r.getStatus() != Receipt.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_CLOSED", "This receipt is already closed");
        }
        return r;
    }

    private Receipt visible(CurrentUser cu, Receipt r) {
        if (r == null || !canSee(cu, r.getWarehouseId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND", "Receipt not found");
        }
        return r;
    }

    private boolean canSee(CurrentUser cu, long warehouseId) {
        List<Long> ids = warehouses.assignedIds(cu);
        return ids == null || ids.contains(warehouseId);
    }

    private ReceiptDetail detail(Receipt r) {
        PurchaseOrder o = r.getOrder();
        Map<String, ProductService.OrderProduct> catalog = products.forOrder(o.getLines().stream().map(PoLine::getSku).toList());
        List<LineProgress> lines = o.getLines().stream().map(l -> {
            List<ReceiptLine> mine = r.getLines().stream().filter(x -> x.getPoLine() == l).toList();
            BigDecimal here = mine.stream().map(ReceiptLine::getQtyBase).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal damaged = mine.stream().map(ReceiptLine::getDamagedBase).reduce(BigDecimal.ZERO, BigDecimal::add);
            // A closed receipt is already inside qty_received; show it as "this receipt", not twice.
            BigDecimal before = r.getStatus() == Receipt.Status.CLOSED ? l.getQtyReceived().subtract(here) : l.getQtyReceived();
            ProductService.OrderProduct p = catalog.get(l.getSku());
            return new LineProgress(l.getLineNo(), l.getSku(), l.getProductName(), p == null ? "" : p.baseUom(),
                    l.orderedBase(), before, here, damaged);
        }).toList();
        List<Scan> scans = r.getLines().stream().map(l -> new Scan(l.getPublicId(), l.getPoLine().getSku(), l.getUom(),
                l.getFactor(), l.getQty(), l.getQtyBase(), l.getDamagedBase(), l.getDamageNote(), l.getCreatedAt())).toList();
        return new ReceiptDetail(r.getPublicId(), r.getNumber(), r.getStatus().name(), o.getPublicId(), o.getNumber(),
                o.getSupplierName(), o.getWarehouseCode(), r.getNote(), r.getCreatedAt(), r.getClosedAt(), lines, scans,
                r.getVersion());
    }
}
