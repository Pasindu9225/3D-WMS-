package lk.wms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Where goods go (v1.2 putaway rules): one product, one category or every product (both null) goes to a fixed bin
 * or into a zone; onlyZone = never outside that zone. What it applies to and where it sends are fixed once made.
 */
@Entity
@Table(name = "putaway_rule")
public class PutawayRule extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(nullable = false)
    private int priority;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(length = 40, updatable = false)
    private String category;

    @Column(name = "bin_id", updatable = false)
    private Long binId;

    @Column(name = "zone_code", length = 10, updatable = false)
    private String zoneCode;

    @Column(name = "only_zone", nullable = false)
    private boolean onlyZone;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** v2.1 (3PL): every product of this client; set alone (no product, no category). */
    @Column(name = "owner_id")
    private Long ownerId;

    @Version
    private int version;

    protected PutawayRule() {
    }

    public PutawayRule(long warehouseId, int priority, Long productId, String category, Long binId, String zoneCode, boolean onlyZone,
            long userId) {
        this.publicId = Ulid.next();
        this.warehouseId = warehouseId;
        this.priority = priority;
        this.productId = productId;
        this.category = category;
        this.binId = binId;
        this.zoneCode = zoneCode;
        this.onlyZone = onlyZone;
        this.createdBy = userId;
        this.createdAt = Instant.now();
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void set(int priority, boolean onlyZone, boolean active) {
        this.priority = priority;
        this.onlyZone = onlyZone && zoneCode != null;
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

    public int getPriority() {
        return priority;
    }

    public Long getProductId() {
        return productId;
    }

    public String getCategory() {
        return category;
    }

    public Long getBinId() {
        return binId;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public boolean isOnlyZone() {
        return onlyZone;
    }

    public boolean isActive() {
        return active;
    }

    public int getVersion() {
        return version;
    }
}
