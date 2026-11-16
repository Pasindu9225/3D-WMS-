package lk.wms.outbound.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.DocNumbers;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.domain.PickLine;
import lk.wms.outbound.domain.PickList;
import lk.wms.outbound.domain.SalesOrder;
import lk.wms.outbound.domain.Shipment;
import lk.wms.outbound.repo.PickListRepository;
import lk.wms.outbound.repo.SalesOrderRepository;
import lk.wms.outbound.repo.ShipmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch (v1.0 task 5). A shipment carries packed orders of one warehouse: their goods leave the dispatch area
 * (one SHIP movement per product and lot) and the orders become SHIPPED, all in one transaction or not at all.
 */
@Service
public class ShipmentService {

    public record OrderRef(String id, String number, String customerName, String deliveryNoteNo, Integer cartons) {
    }

    public record View(String id, String number, String warehouseCode, String vehicleNo, String driverName, String note,
            Instant createdAt, List<OrderRef> orders) {
    }

    static final String KIND = "SH";

    private final ShipmentRepository shipments;
    private final SalesOrderRepository orders;
    private final PickListRepository lists;
    private final DocNumbers numbers;
    private final WarehouseService warehouses;
    private final StockMovementService stock;
    private final AuditService audit;

    public ShipmentService(ShipmentRepository shipments, SalesOrderRepository orders, PickListRepository lists, DocNumbers numbers,
            WarehouseService warehouses, StockMovementService stock, AuditService audit) {
        this.shipments = shipments;
        this.orders = orders;
        this.lists = lists;
        this.numbers = numbers;
        this.warehouses = warehouses;
        this.stock = stock;
        this.audit = audit;
    }

    /** Packed orders of my warehouses, oldest first: what is waiting for a vehicle. */
    @Transactional(readOnly = true)
    public Page<SalesOrderService.Summary> toDispatch(CurrentUser cu, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return orders.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, List.of(SalesOrder.Status.PACKED), "",
                pageable).map(SalesOrderService::summary);
    }

    @Transactional
    public View create(CurrentUser cu, String warehouseId, List<String> orderIds, String vehicleNo, String driverName, String note) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        List<String> distinct = orderIds.stream().distinct().toList();
        List<SalesOrder> locked = orders.lockByPublicIds(distinct); // ordered by id: two dispatchers never deadlock
        if (locked.size() != distinct.size() || locked.stream().anyMatch(o -> o.getWarehouseId() != wh.id())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SO_NOT_FOUND", "An order was not found in this warehouse");
        }
        List<String> notPacked = locked.stream().filter(o -> o.getStatus() != SalesOrder.Status.PACKED).map(SalesOrder::getNumber).toList();
        if (!notPacked.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SO_NOT_PACKED", "Not packed (or already shipped): " + String.join(", ", notPacked));
        }
        Shipment s = shipments.save(new Shipment(numbers.next(cu, KIND), wh.id(), blankToNull(vehicleNo), blankToNull(driverName),
                blankToNull(note), locked.size(), cu.userId()));
        for (SalesOrder o : locked) {
            PickList list = lists.findByOrderId(o.getId()).orElseThrow();
            for (PickLine l : list.getLines()) {
                if (l.getQtyPicked().signum() > 0) {
                    stock.move(new Move(Kind.SHIP, wh.id(), l.getProductId(), l.getBatchNo(), l.getExpiryDate(), Place.DISPATCH, null,
                            l.getQtyPicked(), null, KIND, s.getId(), cu.userId()));
                }
            }
            o.ship(s.getId());
        }
        orders.flush();
        audit.record(cu.tenantId(), cu.userId(), "SHIPPED", "shipment", s.getPublicId(), null,
                Map.of("number", s.getNumber(), "orders", locked.stream().map(SalesOrder::getNumber).toList(),
                        "vehicle", s.getVehicleNo() == null ? "" : s.getVehicleNo()));
        return view(s, locked);
    }

    @Transactional(readOnly = true)
    public Page<View> list(CurrentUser cu, Pageable pageable) {
        List<Long> ids = warehouses.assignedIds(cu);
        return shipments.search(ids == null, ids == null || ids.isEmpty() ? List.of(-1L) : ids, pageable)
                .map(s -> view(s, orders.findByShipmentIdOrderById(s.getId())));
    }

    @Transactional(readOnly = true)
    public View get(CurrentUser cu, String publicId) {
        Shipment s = shipments.findByPublicId(publicId).orElse(null);
        List<Long> ids = s == null ? null : warehouses.assignedIds(cu);
        if (s == null || (ids != null && !ids.contains(s.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SHIPMENT_NOT_FOUND", "Shipment not found");
        }
        return view(s, orders.findByShipmentIdOrderById(s.getId()));
    }

    /** The shipment numbers of these shipments, for order pages. */
    Map<Long, Shipment> byIds(List<Long> ids) {
        return shipments.byInternalIds(ids).stream().collect(Collectors.toMap(Shipment::getId, x -> x));
    }

    private View view(Shipment s, List<SalesOrder> carried) {
        List<OrderRef> refs = new ArrayList<>();
        carried.forEach(o -> refs.add(new OrderRef(o.getPublicId(), o.getNumber(), o.getCustomerName(), o.getDeliveryNoteNo(), o.getCartons())));
        return new View(s.getPublicId(), s.getNumber(), warehouses.codeOf(s.getWarehouseId()), s.getVehicleNo(), s.getDriverName(),
                s.getNote(), s.getCreatedAt(), refs);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
