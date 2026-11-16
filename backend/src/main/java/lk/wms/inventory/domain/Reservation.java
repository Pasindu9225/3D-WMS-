package lk.wms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lk.wms.common.tenant.TenantScoped;

/** Stock in one place set aside for one document line (a sales order line). Written only by StockMovementService. */
@Entity
@Table(name = "reservation")
public class Reservation extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "bin_id", nullable = false, updatable = false)
    private Long binId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "batch_no", length = 40, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(name = "ref_type", nullable = false, length = 10, updatable = false)
    private String refType;

    @Column(name = "ref_id", nullable = false, updatable = false)
    private Long refId;

    @Column(name = "ref_line", nullable = false, updatable = false)
    private int refLine;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reservation() {
    }

    public Reservation(Stock place, BigDecimal qty, String refType, long refId, int refLine, long createdBy) {
        this.warehouseId = place.getWarehouseId();
        this.binId = place.getBinId();
        this.productId = place.getProductId();
        this.batchNo = place.getBatchNo();
        this.expiryDate = place.getExpiryDate();
        this.qty = qty;
        this.refType = refType;
        this.refId = refId;
        this.refLine = refLine;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Same stock place (bin, product, batch, expiry) as this stock row. */
    public boolean isAt(Stock s) {
        return binId.equals(s.getBinId()) && productId.equals(s.getProductId())
                && java.util.Objects.equals(batchNo, s.getBatchNo()) && java.util.Objects.equals(expiryDate, s.getExpiryDate());
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Long getBinId() {
        return binId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public int getRefLine() {
        return refLine;
    }
}
