package lk.wms.layout.domain;

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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/**
 * A reusable rack type. Placed racks copy its size when the layout is published, so editing a
 * template never moves or resizes racks that already exist.
 */
@Entity
@Table(name = "rack_template")
@SQLRestriction("deleted_at is null")
public class RackTemplate extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "depth_mm", nullable = false)
    private int depthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    @Column(nullable = false)
    private short levels;

    @Column(name = "bins_per_level", nullable = false)
    private short binsPerLevel;

    @Column(name = "max_weight_per_level_g", nullable = false)
    private long maxWeightPerLevelG;

    @Version
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected RackTemplate() {
    }

    public RackTemplate(String code, String name, int widthMm, int depthMm, int heightMm, int levels, int binsPerLevel,
            long maxWeightPerLevelG) {
        this.publicId = Ulid.next();
        this.code = code;
        this.createdAt = Instant.now();
        set(name, widthMm, depthMm, heightMm, levels, binsPerLevel, maxWeightPerLevelG);
    }

    public final void set(String name, int widthMm, int depthMm, int heightMm, int levels, int binsPerLevel,
            long maxWeightPerLevelG) {
        this.name = name;
        this.widthMm = widthMm;
        this.depthMm = depthMm;
        this.heightMm = heightMm;
        this.levels = (short) levels;
        this.binsPerLevel = (short) binsPerLevel;
        this.maxWeightPerLevelG = maxWeightPerLevelG;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.deletedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getWidthMm() {
        return widthMm;
    }

    public int getDepthMm() {
        return depthMm;
    }

    public int getHeightMm() {
        return heightMm;
    }

    public int getLevels() {
        return levels;
    }

    public int getBinsPerLevel() {
        return binsPerLevel;
    }

    public long getMaxWeightPerLevelG() {
        return maxWeightPerLevelG;
    }
}
