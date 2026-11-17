package lk.wms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Keep between min and max of one product in one pick-face bin (v1.2 refill). Quantities in base units. */
@Entity
@Table(name = "refill_rule")
public class RefillRule extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "bin_id", nullable = false, updatable = false)
    private Long binId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "min_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal minQty;

    @Column(name = "max_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal maxQty;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private int version;

    protected RefillRule() {
    }

    public RefillRule(long warehouseId, long binId, long productId, BigDecimal minQty, BigDecimal maxQty, long userId) {
        this.publicId = Ulid.next();
        this.warehouseId = warehouseId;
        this.binId = binId;
        this.productId = productId;
        this.minQty = minQty;
        this.maxQty = maxQty;
        this.createdBy = userId;
        this.createdAt = Instant.now();
    }

    public void set(BigDecimal minQty, BigDecimal maxQty, boolean active) {
        this.minQty = minQty;
        this.maxQty = maxQty;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
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

    public BigDecimal getMinQty() {
        return minQty;
    }

    public BigDecimal getMaxQty() {
        return maxQty;
    }

    public boolean isActive() {
        return active;
    }

    public int getVersion() {
        return version;
    }
}
