package lk.wms.outbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One vehicle trip: the packed orders it carried left the warehouse together. Never changed once made. */
@Entity
@Table(name = "shipment")
public class Shipment extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "vehicle_no", length = 20, updatable = false)
    private String vehicleNo;

    @Column(name = "driver_name", length = 120, updatable = false)
    private String driverName;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "order_count", nullable = false, updatable = false)
    private int orderCount;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Shipment() {
    }

    public Shipment(String number, long warehouseId, String vehicleNo, String driverName, String note, int orderCount, long createdBy) {
        this.publicId = Ulid.next();
        this.number = number;
        this.warehouseId = warehouseId;
        this.vehicleNo = vehicleNo;
        this.driverName = driverName;
        this.note = note;
        this.orderCount = orderCount;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getNumber() {
        return number;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public String getVehicleNo() {
        return vehicleNo;
    }

    public String getDriverName() {
        return driverName;
    }

    public String getNote() {
        return note;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
