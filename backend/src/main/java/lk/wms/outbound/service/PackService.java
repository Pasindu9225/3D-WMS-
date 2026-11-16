package lk.wms.outbound.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.domain.PickLine;
import lk.wms.outbound.domain.PickList;
import lk.wms.outbound.domain.SalesOrder;
import lk.wms.outbound.domain.SalesOrder.Status;
import lk.wms.outbound.domain.SoLine;
import lk.wms.outbound.repo.PickListRepository;
import lk.wms.outbound.repo.SalesOrderRepository;
import lk.wms.tenant.service.TenantService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Packing (v1.0 task 4). The packer scans every product; finishing sends the counts, which must equal what was
 * picked, product by product. The order becomes PACKED and gets its delivery note number. Packing slip and delivery
 * note PDFs come from the same figures.
 */
@Service
public class PackService {

    /** A code the screen can scan for a product, and how many base units it counts. */
    public record ScanCode(String code, String uom, int factor) {
    }

    /** One product to pack: ordered and picked in base units, and every code that may be scanned for it. */
    public record PackItem(String sku, String name, String baseUom, BigDecimal ordered, BigDecimal picked, List<ScanCode> codes) {
    }

    public record PackView(String id, String number, String status, String customerName, String customerRef,
            String warehouseCode, List<PackItem> items, int version) {
    }

    public record Count(String sku, BigDecimal qty) {
    }

    /** Everything the two documents print. */
    public record Papers(String company, String number, String deliveryNoteNo, String customerCode, String customerName,
            String customerRef, String shipTo, String warehouseCode, LocalDate requestedDate, Instant packedAt, int cartons,
            Integer weightG, List<PackItem> items) {
    }

    private final SalesOrderRepository orders;
    private final PickListRepository lists;
    private final DocNumbers numbers;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final TenantService tenants;
    private final AuditService audit;

    public PackService(SalesOrderRepository orders, PickListRepository lists, DocNumbers numbers, ProductService products,
            WarehouseService warehouses, TenantService tenants, AuditService audit) {
        this.orders = orders;
        this.lists = lists;
        this.numbers = numbers;
        this.products = products;
        this.warehouses = warehouses;
        this.tenants = tenants;
        this.audit = audit;
    }

    /** Picked orders of my warehouses, oldest first: the packing table's queue. */
    @Transactional(readOnly = true)
    public Page<SalesOrderService.Summary> toPack(CurrentUser cu, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return orders.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, List.of(Status.PICKED), "", pageable)
                .map(SalesOrderService::summary);
    }

    @Transactional(readOnly = true)
    public PackView view(CurrentUser cu, String publicId) {
        SalesOrder o = find(cu, publicId);
        return new PackView(o.getPublicId(), o.getNumber(), o.getStatus().name(), o.getCustomerName(), o.getCustomerRef(),
                o.getWarehouseCode(), items(o), o.getVersion());
    }

    /** Finishes packing: counts must equal what was picked; the order is version-checked so it packs only once. */
    @Transactional
    public PackView finish(CurrentUser cu, String publicId, int version, int cartons, Integer weightG, List<Count> counts) {
        SalesOrder o = find(cu, publicId);
        if (o.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_CHANGED", "Someone else changed this order; reload it");
        }
        if (o.getStatus() != Status.PICKED) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_NOT_PICKED", "Only a picked order can be packed");
        }
        Map<String, BigDecimal> sent = new LinkedHashMap<>();
        counts.forEach(c -> sent.merge(c.sku().trim().toUpperCase(java.util.Locale.ROOT), c.qty(), BigDecimal::add));
        List<String> diffs = new ArrayList<>();
        List<PackItem> items = items(o);
        for (PackItem i : items) {
            BigDecimal got = sent.getOrDefault(i.sku(), BigDecimal.ZERO);
            if (got.compareTo(i.picked()) != 0) {
                diffs.add(i.sku() + ": scanned " + plain(got) + ", picked " + plain(i.picked()) + " " + i.baseUom());
            }
            sent.remove(i.sku());
        }
        sent.forEach((sku, qty) -> diffs.add(sku + ": scanned " + plain(qty) + ", not on this order"));
        if (!diffs.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PACK_MISMATCH", String.join("; ", diffs));
        }
        o.pack(numbers.next(cu, "DN"), cartons, weightG, cu.userId());
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "SO_PACKED", "sales_order", o.getPublicId(), null,
                Map.of("number", o.getNumber(), "deliveryNote", o.getDeliveryNoteNo(), "cartons", cartons));
        return new PackView(o.getPublicId(), o.getNumber(), o.getStatus().name(), o.getCustomerName(), o.getCustomerRef(),
                o.getWarehouseCode(), items, o.getVersion());
    }

    /** The figures for the packing slip and the delivery note; only once packed. */
    @Transactional(readOnly = true)
    public Papers papers(CurrentUser cu, String publicId) {
        SalesOrder o = find(cu, publicId);
        if (o.getDeliveryNoteNo() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_NOT_PACKED", "This order is not packed yet");
        }
        return new Papers(tenants.name(cu.tenantId()), o.getNumber(), o.getDeliveryNoteNo(), o.getCustomerCode(),
                o.getCustomerName(), o.getCustomerRef(), o.getShipTo(), o.getWarehouseCode(), o.getRequestedDate(),
                o.getPackedAt(), o.getCartons(), o.getWeightG(), items(o));
    }

    // ---------------------------------------------------------------- helpers

    /** One row per product: ordered (all lines) and picked (all pick lines), in base units. */
    private List<PackItem> items(SalesOrder o) {
        Map<Long, BigDecimal> ordered = new LinkedHashMap<>();
        for (SoLine l : o.getLines()) {
            ordered.merge(l.getProductId(), l.orderedBase(), BigDecimal::add);
        }
        Map<Long, BigDecimal> picked = new LinkedHashMap<>();
        PickList list = lists.findByOrderId(o.getId()).orElse(null);
        if (list != null) {
            for (PickLine l : list.getLines()) {
                picked.merge(l.getProductId(), l.getQtyPicked(), BigDecimal::add);
            }
        }
        Map<Long, ProductService.Basic> basics = products.basics(ordered.keySet());
        List<PackItem> out = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : ordered.entrySet()) {
            ProductService.Basic b = basics.get(e.getKey());
            ProductService.ProductView p = products.get(b.publicId());
            List<ScanCode> codes = new ArrayList<>();
            codes.add(new ScanCode(p.sku(), p.baseUom(), 1));
            if (p.barcode() != null) {
                codes.add(new ScanCode(p.barcode(), p.baseUom(), 1));
            }
            p.units().stream().filter(u -> u.barcode() != null).forEach(u -> codes.add(new ScanCode(u.barcode(), u.uom(), u.factor())));
            out.add(new PackItem(p.sku(), p.name(), p.baseUom(), e.getValue(), picked.getOrDefault(e.getKey(), BigDecimal.ZERO), codes));
        }
        return out;
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

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
