package lk.wms.inbound.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.wms.catalog.service.PartnerService;
import lk.wms.catalog.service.ProductService;
import lk.wms.catalog.service.ProductService.OrderProduct;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.domain.PoLine;
import lk.wms.inbound.domain.PurchaseOrder;
import lk.wms.inbound.domain.PurchaseOrder.Party;
import lk.wms.inbound.domain.PurchaseOrder.Status;
import lk.wms.inbound.repo.DocCounterRepository;
import lk.wms.inbound.repo.PurchaseOrderRepository;
import lk.wms.layout.service.WarehouseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders (v0.4 task 3). A draft can be edited; approving fixes it for receiving (task 4); a draft or an
 * approved order with nothing received can be cancelled with a reason. Users only see orders of warehouses they
 * are assigned to (company admins see all).
 */
@Service
public class PurchaseOrderService {

    public record LineSpec(String sku, String uom, BigDecimal qty, Long unitCostMinor) {
    }

    public record OrderSpec(String supplierCode, String warehouseId, LocalDate expectedDate, String note, List<LineSpec> lines) {
    }

    public record Summary(String id, String number, String status, String supplierCode, String supplierName,
            String warehouseCode, LocalDate expectedDate, long totalMinor, int lineCount, Instant createdAt) {
    }

    public record LineView(int lineNo, String sku, String productName, String uom, int factor, BigDecimal qty,
            long unitCostMinor, long totalMinor, BigDecimal qtyReceived) {
    }

    public record Detail(String id, String number, String status, String supplierCode, String supplierName,
            String warehouseId, String warehouseCode, LocalDate expectedDate, String note, long totalMinor,
            Instant createdAt, Instant approvedAt, Instant cancelledAt, String cancelReason, List<LineView> lines,
            int version) {
    }

    static final String KIND = "PO";

    private final PurchaseOrderRepository orders;
    private final DocCounterRepository counters;
    private final PartnerService partners;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final AuditService audit;

    public PurchaseOrderService(PurchaseOrderRepository orders, DocCounterRepository counters, PartnerService partners,
            ProductService products, WarehouseService warehouses, AuditService audit) {
        this.orders = orders;
        this.counters = counters;
        this.partners = partners;
        this.products = products;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, Status status, String query, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return orders.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, status,
                ProductService.likeText(query), pageable).map(PurchaseOrderService::summary);
    }

    @Transactional(readOnly = true)
    public Detail get(CurrentUser cu, String publicId) {
        return detail(find(cu, publicId));
    }

    @Transactional
    public Detail create(CurrentUser cu, OrderSpec s) {
        PurchaseOrder o = new PurchaseOrder(nextNumber(cu), cu.userId());
        apply(cu, o, s);
        o = orders.saveAndFlush(o);
        audit.record(cu.tenantId(), cu.userId(), "PO_CREATED", "purchase_order", o.getPublicId(), null, summary(o));
        return detail(o);
    }

    @Transactional
    public Detail update(CurrentUser cu, String publicId, int version, OrderSpec s) {
        PurchaseOrder o = current(cu, publicId, version);
        if (o.getStatus() != Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "PO_NOT_DRAFT", "Only a draft order can be changed");
        }
        Summary before = summary(o);
        apply(cu, o, s);
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "PO_UPDATED", "purchase_order", o.getPublicId(), before, summary(o));
        return detail(o);
    }

    @Transactional
    public Detail approve(CurrentUser cu, String publicId, int version) {
        PurchaseOrder o = current(cu, publicId, version);
        if (o.getStatus() != Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "PO_NOT_DRAFT", "Only a draft order can be approved");
        }
        o.approve(cu.userId());
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "PO_APPROVED", "purchase_order", o.getPublicId(), null, summary(o));
        return detail(o);
    }

    @Transactional
    public Detail cancel(CurrentUser cu, String publicId, int version, String reason) {
        PurchaseOrder o = current(cu, publicId, version);
        boolean received = o.getLines().stream().anyMatch(l -> l.getQtyReceived().signum() > 0);
        if ((o.getStatus() != Status.DRAFT && o.getStatus() != Status.APPROVED) || received) {
            throw new ApiException(HttpStatus.CONFLICT, "PO_CANNOT_CANCEL",
                    "Only a draft or approved order with nothing received can be cancelled");
        }
        o.cancel(cu.userId(), reason.trim());
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "PO_CANCELLED", "purchase_order", o.getPublicId(), null,
                Map.of("number", o.getNumber(), "reason", o.getCancelReason()));
        return detail(o);
    }

    // ---------------------------------------------------------------- helpers

    private void apply(CurrentUser cu, PurchaseOrder o, OrderSpec s) {
        PartnerService.SupplierRef supplier = partners.supplierByCode(s.supplierCode());
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, s.warehouseId());
        Map<String, OrderProduct> found = products.forOrder(s.lines().stream().map(LineSpec::sku).toList());
        Set<String> seen = new HashSet<>();
        List<PoLine.Spec> lines = s.lines().stream().map(l -> {
            OrderProduct p = found.get(l.sku());
            if (p == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND", "Product " + l.sku() + " not found");
            }
            Integer factor = p.factors().get(l.uom());
            if (factor == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UOM_NOT_FOUND",
                        l.sku() + " has no unit " + l.uom() + "; use one of " + String.join(", ", p.factors().keySet()));
            }
            if (!seen.add(l.sku() + "|" + l.uom())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_LINE", l.sku() + " in " + l.uom() + " is on the order twice");
            }
            long cost = l.unitCostMinor() != null ? l.unitCostMinor()
                    : p.costMinor() == null ? 0 : p.costMinor() * factor; // at most 1e12 x 1e6: fits a long
            return new PoLine.Spec(p.id(), p.sku(), p.name(), l.uom(), factor, l.qty().setScale(3, RoundingMode.UNNECESSARY), cost);
        }).toList();
        String note = s.note() == null || s.note().isBlank() ? null : s.note().trim();
        try {
            o.set(new Party(supplier.id(), supplier.code(), supplier.name()), new Party(wh.id(), wh.code(), null),
                    s.expectedDate(), note, lines);
        } catch (ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PO_TOO_LARGE", "The order total is too large");
        }
    }

    /** PO-000001, PO-000002 ... per company, never repeated: the counter row stays locked until commit. */
    private String nextNumber(CurrentUser cu) {
        counters.bump(cu.tenantId(), KIND);
        return String.format("%s-%06d", KIND, counters.taken(KIND));
    }

    /** The order if this user may see its warehouse; otherwise it does not exist for them. */
    private PurchaseOrder find(CurrentUser cu, String publicId) {
        PurchaseOrder o = orders.findByPublicId(publicId).orElse(null);
        List<Long> ids = o == null ? null : warehouses.assignedIds(cu);
        if (o == null || (ids != null && !ids.contains(o.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PO_NOT_FOUND", "Purchase order not found");
        }
        return o;
    }

    private PurchaseOrder current(CurrentUser cu, String publicId, int version) {
        PurchaseOrder o = find(cu, publicId);
        if (o.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "PO_CHANGED", "Someone else changed this order; reload it");
        }
        return o;
    }

    static Summary summary(PurchaseOrder o) {
        return new Summary(o.getPublicId(), o.getNumber(), o.getStatus().name(), o.getSupplierCode(), o.getSupplierName(),
                o.getWarehouseCode(), o.getExpectedDate(), o.getTotalMinor(), o.getLineCount(), o.getCreatedAt());
    }

    private Detail detail(PurchaseOrder o) {
        List<LineView> lines = o.getLines().stream().map(l -> new LineView(l.getLineNo(), l.getSku(), l.getProductName(),
                l.getUom(), l.getFactor(), l.getQty(), l.getUnitCostMinor(), l.totalMinor(), l.getQtyReceived())).toList();
        return new Detail(o.getPublicId(), o.getNumber(), o.getStatus().name(), o.getSupplierCode(), o.getSupplierName(),
                warehouses.publicIdOf(o.getWarehouseId()), o.getWarehouseCode(), o.getExpectedDate(), o.getNote(),
                o.getTotalMinor(), o.getCreatedAt(), o.getApprovedAt(), o.getCancelledAt(), o.getCancelReason(), lines,
                o.getVersion());
    }
}
