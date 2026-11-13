package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A storage slot: one position on one rack level. Its id never changes across publishes (stock will
 * point at it); its code follows the rack and zone. Capacity comes from the rack type unless a
 * planner set it by hand (customCapacity), which publish then keeps.
 */
@Entity
@Table(name = "bin")
public class Bin extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "level_id", nullable = false, updatable = false)
    private Long levelId;

    @Column(name = "position_no", nullable = false, updatable = false)
    private short positionNo;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "max_weight_g", nullable = false)
    private long maxWeightG;

    @Column(name = "max_volume_cm3", nullable = false)
    private long maxVolumeCm3;

    @Column(name = "custom_capacity", nullable = false)
    private boolean customCapacity;

    @Column(name = "is_pick_face", nullable = false)
    private boolean pickFace;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;

    @Version
    private int version;

    protected Bin() {
    }

    public Bin(long warehouseId, long levelId, int positionNo) {
        this.publicId = Ulid.next();
        this.warehouseId = warehouseId;
        this.levelId = levelId;
        this.positionNo = (short) positionNo;
    }

    /** Publish: new code, and the template's capacity unless the planner set one. */
    public void layOut(String code, long maxWeightG, long maxVolumeCm3) {
        this.code = code;
        if (!customCapacity) {
            this.maxWeightG = maxWeightG;
            this.maxVolumeCm3 = maxVolumeCm3;
        }
    }

    /** Publish, step 1: a placeholder code, so codes can move between bins without a unique-key clash. */
    public void parkCode() {
        this.code = "~" + publicId;
    }

    /** A planner's single-bin edit; null leaves a value as it is. */
    public void edit(Long maxWeightG, Long maxVolumeCm3, Boolean pickFace, Boolean blocked) {
        if (maxWeightG != null) {
            this.maxWeightG = maxWeightG;
            this.customCapacity = true;
        }
        if (maxVolumeCm3 != null) {
            this.maxVolumeCm3 = maxVolumeCm3;
            this.customCapacity = true;
        }
        if (pickFace != null) {
            this.pickFace = pickFace;
        }
        if (blocked != null) {
            this.blocked = blocked;
        }
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

    public Long getLevelId() {
        return levelId;
    }

    public int getPositionNo() {
        return positionNo;
    }

    public String getCode() {
        return code;
    }

    public long getMaxWeightG() {
        return maxWeightG;
    }

    public long getMaxVolumeCm3() {
        return maxVolumeCm3;
    }

    public boolean isCustomCapacity() {
        return customCapacity;
    }

    public boolean isPickFace() {
        return pickFace;
    }

    public boolean isBlocked() {
        return blocked;
    }
}
