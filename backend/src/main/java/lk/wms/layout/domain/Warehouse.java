package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** A building with a floor outline. Archived warehouses are hidden everywhere (soft delete). */
@Entity
@Table(name = "warehouse")
@SQLRestriction("deleted_at is null")
public class Warehouse extends TenantScoped {

    public enum LayoutStatus { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** JSON [[x_mm, y_mm], ...]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "floor_polygon", nullable = false)
    private String floorPolygon;

    @Column(name = "wall_height_mm", nullable = false)
    private int wallHeightMm;

    @Column(name = "bin_code_pattern", nullable = false, length = 60)
    private String binCodePattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "layout_status", nullable = false)
    private LayoutStatus layoutStatus;

    @Version
    private int version;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Warehouse() {
    }

    /** New warehouse with a rectangular floor; the editor can reshape it later. */
    public Warehouse(String code, String name, int widthMm, int lengthMm, int wallHeightMm, Long createdBy) {
        this.publicId = Ulid.next();
        this.code = code;
        this.name = name;
        this.floorPolygon = "[[0,0],[" + widthMm + ",0],[" + widthMm + "," + lengthMm + "],[0," + lengthMm + "]]";
        this.wallHeightMm = wallHeightMm;
        this.binCodePattern = "{zone}-{rack}-{level}-{bin}";
        this.layoutStatus = LayoutStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void update(String name, Integer wallHeightMm) {
        if (name != null) {
            this.name = name;
        }
        if (wallHeightMm != null) {
            this.wallHeightMm = wallHeightMm;
        }
        this.updatedAt = Instant.now();
    }

    /** The published floor becomes the warehouse outline. */
    public void publish(String floorPolygonJson) {
        this.floorPolygon = floorPolygonJson;
        this.layoutStatus = LayoutStatus.PUBLISHED;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.deletedAt = Instant.now();
        this.updatedAt = deletedAt;
    }

    /** Internal ID: never in an API response. */
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

    public String getFloorPolygon() {
        return floorPolygon;
    }

    public int getWallHeightMm() {
        return wallHeightMm;
    }

    public String getBinCodePattern() {
        return binCodePattern;
    }

    public LayoutStatus getLayoutStatus() {
        return layoutStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
