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
import java.util.Map;
import java.util.Set;
import lk.wms.catalog.service.ImportResult;
import lk.wms.catalog.service.ImportResult.RowError;
import lk.wms.catalog.service.PartnerService;
import lk.wms.catalog.service.PartnerService.CustomerRef;
import lk.wms.catalog.service.ProductService;
import lk.wms.catalog.service.ProductService.OrderProduct;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Allocation;
import lk.wms.inventory.service.StockMovementService.Need;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.domain.SalesOrder;
import lk.wms.outbound.domain.SalesOrder.Header;
import lk.wms.outbound.domain.SalesOrder.Status;
import lk.wms.outbound.domain.SoLine;
import lk.wms.outbound.repo.SalesOrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Sales orders (v1.0 task 1): drafted by hand or from a CSV file, edited while a draft, cancelled with a reason.
 * Releasing for picking comes in task 3. Users only see orders of warehouses they are assigned to (company admins
 * see all).
 */
@Service
public class SalesOrderService {

    public record LineSpec(String sku, String uom, BigDecimal qty) {
    }

    public record OrderSpec(String customerCode, String customerRef, String warehouseId, String shipTo,
            LocalDate requestedDate, String note, List<LineSpec> lines) {
    }

    /** One CSV row, already checked field by field; rows with the same orderRef make one order. */
    public record ImportLine(int row, String orderRef, String customerCode, String warehouseCode, LocalDate requestedDate,
            String shipTo, String note, LineSpec line) {
    }

    public record Summary(String id, String number, String status, String customerCode, String customerName,
            String customerRef, String warehouseCode, LocalDate requestedDate, int lineCount, Instant createdAt) {
    }

    public record LineView(int lineNo, String sku, String productName, String uom, int factor, BigDecimal qty) {
    }

    /** Stock set aside for a line: where, which lot, how much (base units). */
    public record ReservedView(int lineNo, String sku, String binCode, String batchNo, LocalDate expiryDate, BigDecimal qty) {
    }

    public record Detail(String id, String number, String status, String customerCode, String customerName,
            String customerRef, String warehouseId, String warehouseCode, String shipTo, LocalDate requestedDate,
            String note, Instant createdAt, Instant releasedAt, Instant cancelledAt, String cancelReason, List<LineView> lines,
            List<ReservedView> reserved, PickService.Detail pick, String deliveryNoteNo, Integer cartons, Instant packedAt,
            Shipped shipped, int version) {
    }

    /** The trip an order left on. */
    public record Shipped(String shipmentId, String number, String vehicleNo, String driverName, Instant at) {
    }

    static final String KIND = "SO";
    static final String REF = "SO";
    static final int MAX_LINES = 200;

    private final SalesOrderRepository orders;
    private final DocNumbers numbers;
    private final PartnerService partners;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final AuditService audit;
    private final StockMovementService stock;
    private final PickService picks;
    private final ShipmentService shipments;

    public SalesOrderService(SalesOrderRepository orders, DocNumbers numbers, PartnerService partners,
            ProductService products, WarehouseService warehouses, AuditService audit, StockMovementService stock,
            PickService picks, ShipmentService shipments) {
        this.stock = stock;
        this.picks = picks;
        this.shipments = shipments;
        this.orders = orders;
        this.numbers = numbers;
        this.partners = partners;
        this.products = products;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, java.util.Collection<Status> statuses, String query, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return orders.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, statuses,
                ProductService.likeText(query), pageable).map(SalesOrderService::summary);
    }

    /** The same list with the status by name (null = all), for other modules. */
    @Transactional(readOnly = true)
    public Page<Summary> list(CurrentUser cu, String status, String query, Pageable pageable) {
        return list(cu, status == null ? List.of(Status.values()) : List.of(Status.valueOf(status)), query, pageable);
    }

    @Transactional(readOnly = true)
    public Detail get(CurrentUser cu, String publicId) {
        return detail(find(cu, publicId));
    }

    @Transactional
    public Detail create(CurrentUser cu, OrderSpec s) {
        SalesOrder o = new SalesOrder(numbers.next(cu, KIND), cu.userId());
        apply(cu, o, s);
        o = save(o);
        audit.record(cu.tenantId(), cu.userId(), "SO_CREATED", "sales_order", o.getPublicId(), null, summary(o));
        return detail(o);
    }

    @Transactional
    public Detail update(CurrentUser cu, String publicId, int version, OrderSpec s) {
        SalesOrder o = current(cu, publicId, version);
        if (o.getStatus() != Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_NOT_DRAFT", "Only a draft order can be changed");
        }
        Summary before = summary(o);
        apply(cu, o, s);
        save(o);
        audit.record(cu.tenantId(), cu.userId(), "SO_UPDATED", "sales_order", o.getPublicId(), before, summary(o));
        return detail(o);
    }

    /**
     * Releases a draft for picking: every line is reserved in full (FIFO by receive date) or nothing is, and the
     * error lists what is short. The order row is version-checked, so two clicks cannot release it twice.
     */
    @Transactional
    public Detail release(CurrentUser cu, String publicId, int version) {
        SalesOrder o = current(cu, publicId, version);
        if (o.getStatus() != Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_NOT_DRAFT", "Only a draft order can be released");
        }
        warehouses.requireAccess(cu, o.getWarehouseId()); // still live and still this user's
        Map<Long, String> baseUom = new HashMap<>();
        products.forOrder(o.getLines().stream().map(SoLine::getSku).toList()).values().forEach(p -> baseUom.put(p.id(), p.baseUom()));
        List<Need> needs = o.getLines().stream().map(l -> new Need(l.getLineNo(), l.getProductId(), l.orderedBase(),
                l.getSku() + " (" + baseUom.getOrDefault(l.getProductId(), l.getUom()) + ")")).toList();
        List<Allocation> got = stock.reserve(o.getWarehouseId(), REF, o.getId(), needs, cu.userId());
        Map<Integer, PickService.LineInfo> info = new HashMap<>();
        o.getLines().forEach(l -> info.put(l.getLineNo(), new PickService.LineInfo(l.getSku(), l.getProductName(),
                baseUom.getOrDefault(l.getProductId(), l.getUom()))));
        picks.createFor(cu, o, got, info);
        o.release(cu.userId());
        orders.flush(); // version check: a second release of the same version fails here and rolls back its reservations
        audit.record(cu.tenantId(), cu.userId(), "SO_RELEASED", "sales_order", o.getPublicId(), null,
                Map.of("number", o.getNumber(), "reserved", got.stream().map(a -> Map.of("line", a.line(), "bin", a.binCode(),
                        "qty", a.qty())).toList()));
        return detail(o);
    }

    @Transactional
    public Detail cancel(CurrentUser cu, String publicId, int version, String reason) {
        SalesOrder o = current(cu, publicId, version);
        if (o.getStatus() != Status.DRAFT && o.getStatus() != Status.RELEASED) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_CANNOT_CANCEL", "Only a draft or released order can be cancelled");
        }
        if (o.getStatus() == Status.RELEASED) {
            picks.dropFor(o); // refuses when picking has already begun
            stock.unreserve(REF, o.getId());
        }
        o.cancel(cu.userId(), reason.trim());
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "SO_CANCELLED", "sales_order", o.getPublicId(), null,
                Map.of("number", o.getNumber(), "reason", o.getCancelReason()));
        return detail(o);
    }

    /**
     * CSV import (SEC-46): rows with the same order ref become one draft order, all or nothing. Every customer,
     * warehouse, product and unit must exist; a ref already used for that customer (or twice in the file) is an error.
     */
    @Transactional
    public ImportResult importRows(CurrentUser cu, List<ImportLine> rows, List<RowError> errors) {
        Map<String, List<ImportLine>> byRef = new LinkedHashMap<>();
        rows.forEach(r -> byRef.computeIfAbsent(r.orderRef(), k -> new ArrayList<>()).add(r));
        Map<String, CustomerRef> customers = partners.customersByCode(rows.stream().map(ImportLine::customerCode).toList());
        Map<String, OrderProduct> found = products.forOrder(rows.stream().map(r -> r.line().sku()).toList());
        Map<String, WarehouseService.WarehouseRef> whs = new HashMap<>();
        Set<String> taken = new HashSet<>();
        List<Long> customerIds = customers.values().stream().map(CustomerRef::id).toList();
        if (!customerIds.isEmpty()) {
            orders.refsTaken(customerIds, byRef.keySet()).forEach(t -> taken.add(t[0] + "|" + t[1]));
        }

        List<SalesOrder> made = new ArrayList<>();
        for (List<ImportLine> group : byRef.values()) {
            ImportLine first = group.get(0);
            CustomerRef customer = customers.get(first.customerCode());
            if (customer == null) {
                errors.add(new RowError(first.row(), "customer_code", "No customer " + first.customerCode()));
            } else if (taken.contains(customer.id() + "|" + first.orderRef())) {
                errors.add(new RowError(first.row(), "order_ref", "Order " + first.orderRef() + " of " + customer.code()
                        + " is already in the system"));
            }
            WarehouseService.WarehouseRef wh = whs.computeIfAbsent(first.warehouseCode(), code -> {
                try {
                    return warehouses.requireAccessByCode(cu, code);
                } catch (ApiException e) {
                    return null;
                }
            });
            if (wh == null) {
                errors.add(new RowError(first.row(), "warehouse_code", "No warehouse " + first.warehouseCode()));
            }
            if (group.size() > MAX_LINES) {
                errors.add(new RowError(first.row(), "order_ref", "Order " + first.orderRef() + " has more than " + MAX_LINES + " lines"));
            }
            Set<String> seen = new HashSet<>();
            List<SoLine.Spec> lines = new ArrayList<>();
            for (ImportLine r : group) {
                if (!r.customerCode().equals(first.customerCode()) || !r.warehouseCode().equals(first.warehouseCode())) {
                    errors.add(new RowError(r.row(), "order_ref", "Rows of order " + r.orderRef()
                            + " must have the same customer and warehouse (see row " + first.row() + ")"));
                    continue;
                }
                try {
                    lines.add(line(found, r.line(), seen));
                } catch (ApiException e) {
                    errors.add(new RowError(r.row(), "sku", e.getMessage()));
                }
            }
            if (errors.isEmpty()) {
                SalesOrder o = new SalesOrder(numbers.next(cu, KIND), cu.userId());
                o.set(header(customer, first.orderRef(), wh, first.shipTo(), first.requestedDate(), first.note()), lines);
                made.add(o);
            }
        }
        ImportResult result = ImportResult.of(made.size(), 0, errors);
        if (!result.saved()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return result;
        }
        orders.saveAll(made);
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "SO_IMPORTED", "sales_order", null, null,
                Map.of("created", made.size(), "numbers", made.stream().map(SalesOrder::getNumber).toList()));
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private void apply(CurrentUser cu, SalesOrder o, OrderSpec s) {
        CustomerRef customer = partners.customerByCode(s.customerCode());
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, s.warehouseId());
        Map<String, OrderProduct> found = products.forOrder(s.lines().stream().map(LineSpec::sku).toList());
        Set<String> seen = new HashSet<>();
        List<SoLine.Spec> lines = s.lines().stream().map(l -> line(found, l, seen)).toList();
        o.set(header(customer, s.customerRef(), wh, s.shipTo(), s.requestedDate(), s.note()), lines);
    }

    private static Header header(CustomerRef c, String ref, WarehouseService.WarehouseRef wh, String shipTo,
            LocalDate requestedDate, String note) {
        String to = blankToNull(shipTo);
        return new Header(c.id(), c.code(), c.name(), blankToNull(ref), wh.id(), wh.code(), to != null ? to : c.address(),
                requestedDate, blankToNull(note));
    }

    /** A checked line, or an ApiException saying what is wrong with it. */
    private static SoLine.Spec line(Map<String, OrderProduct> found, LineSpec l, Set<String> seen) {
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
        return new SoLine.Spec(p.id(), p.sku(), p.name(), l.uom(), factor, l.qty().setScale(3, RoundingMode.UNNECESSARY));
    }

    private SalesOrder save(SalesOrder o) {
        try {
            return orders.saveAndFlush(o);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_REF_EXISTS", "This customer already has an order with that reference");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** The order if this user may see its warehouse; otherwise it does not exist for them. */
    private SalesOrder find(CurrentUser cu, String publicId) {
        SalesOrder o = orders.findByPublicId(publicId).orElse(null);
        List<Long> ids = o == null ? null : warehouses.assignedIds(cu);
        if (o == null || (ids != null && !ids.contains(o.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SO_NOT_FOUND", "Sales order not found");
        }
        return o;
    }

    private SalesOrder current(CurrentUser cu, String publicId, int version) {
        SalesOrder o = find(cu, publicId);
        if (o.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_CHANGED", "Someone else changed this order; reload it");
        }
        return o;
    }

    private Shipped shipped(SalesOrder o) {
        if (o.getShipmentId() == null) {
            return null;
        }
        lk.wms.outbound.domain.Shipment s = shipments.byIds(List.of(o.getShipmentId())).get(o.getShipmentId());
        return new Shipped(s.getPublicId(), s.getNumber(), s.getVehicleNo(), s.getDriverName(), o.getShippedAt());
    }

    /** Orders of a warehouse released and shipped since a moment (the dashboard's "today"). */
    public record DayCounts(long released, long shipped) {
    }

    @Transactional(readOnly = true)
    public DayCounts countsSince(long warehouseId, Instant from) {
        return new DayCounts(orders.countByWarehouseIdAndReleasedAtGreaterThanEqual(warehouseId, from),
                orders.countByWarehouseIdAndShippedAtGreaterThanEqual(warehouseId, from));
    }

    static Summary summary(SalesOrder o) {
        return new Summary(o.getPublicId(), o.getNumber(), o.getStatus().name(), o.getCustomerCode(), o.getCustomerName(),
                o.getCustomerRef(), o.getWarehouseCode(), o.getRequestedDate(), o.getLineCount(), o.getCreatedAt());
    }

    private Detail detail(SalesOrder o) {
        List<LineView> lines = o.getLines().stream().map(l -> new LineView(l.getLineNo(), l.getSku(), l.getProductName(),
                l.getUom(), l.getFactor(), l.getQty())).toList();
        Map<Integer, String> skuOf = new HashMap<>();
        o.getLines().forEach(l -> skuOf.put(l.getLineNo(), l.getSku()));
        List<ReservedView> reserved = o.getStatus() == Status.DRAFT || o.getStatus() == Status.CANCELLED ? List.of()
                : stock.reserved(REF, o.getId()).stream().map(a -> new ReservedView(a.line(), skuOf.get(a.line()), a.binCode(),
                        a.batchNo(), a.expiry(), a.qty())).toList();
        return new Detail(o.getPublicId(), o.getNumber(), o.getStatus().name(), o.getCustomerCode(), o.getCustomerName(),
                o.getCustomerRef(), warehouses.publicIdOf(o.getWarehouseId()), o.getWarehouseCode(), o.getShipTo(),
                o.getRequestedDate(), o.getNote(), o.getCreatedAt(), o.getReleasedAt(), o.getCancelledAt(), o.getCancelReason(),
                lines, reserved, o.getStatus() == Status.DRAFT ? null : picks.forOrder(o), o.getDeliveryNoteNo(), o.getCartons(),
                o.getPackedAt(), shipped(o), o.getVersion());
    }
}
