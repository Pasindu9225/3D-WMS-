package lk.wms.catalog.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** A product. Stock is always counted in its base unit; larger units (case, pallet) are conversions. */
@Entity
@Table(name = "product")
@SQLRestriction("deleted_at is null")
public class Product extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 40, updatable = false)
    private String sku;

    @Column(length = 64)
    private String barcode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "base_uom", nullable = false, length = 10)
    private String baseUom;

    @Column(name = "weight_g")
    private Integer weightG;

    @Column(name = "volume_cm3")
    private Integer volumeCm3;

    @Column(name = "track_batch", nullable = false)
    private boolean trackBatch;

    @Column(name = "track_expiry", nullable = false)
    private boolean trackExpiry;

    @Column(name = "cost_minor")
    private Long costMinor;

    /** Low stock at or below this many base units (dashboard); null = not watched. */
    @Column(name = "reorder_point", precision = 14, scale = 3)
    private java.math.BigDecimal reorderPoint;

    /** A, B or C by pick frequency (v1.1). Written only by the ABC job's bulk update, so a product edit never resets it. */
    @Column(name = "abc_class", insertable = false, updatable = false)
    private String abcClass;

    @Column(name = "abc_at", insertable = false, updatable = false)
    private java.time.Instant abcAt;

    /** Free text used by putaway rules (v1.2), e.g. Frozen. */
    @Column(length = 40)
    private String category;

    @Column(name = "image_file_id")
    private Long imageFileId;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("factor")
    private List<ProductUom> units = new ArrayList<>();

    @Version
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Product() {
    }

    public Product(String sku) {
        this.publicId = Ulid.next();
        this.sku = sku;
        this.createdAt = Instant.now();
    }

    public void set(String name, String barcode, String baseUom, Integer weightG, Integer volumeCm3, boolean trackBatch,
            boolean trackExpiry, Long costMinor, java.math.BigDecimal reorderPoint, String category) {
        this.reorderPoint = reorderPoint;
        this.category = category;
        this.name = name;
        this.barcode = barcode;
        this.baseUom = baseUom;
        this.weightG = weightG;
        this.volumeCm3 = volumeCm3;
        this.trackBatch = trackBatch;
        this.trackExpiry = trackExpiry;
        this.costMinor = costMinor;
        this.updatedAt = Instant.now();
    }

    /** A larger unit as the planner enters it. */
    public record UnitSpec(String uom, int factor, String barcode) {
    }

    /** Replaces the larger units; rows for units that stay are kept (same id), the others are removed. */
    public void setUnits(List<UnitSpec> specs) {
        units.removeIf(u -> specs.stream().noneMatch(s -> s.uom().equals(u.getUom())));
        for (UnitSpec s : specs) {
            ProductUom row = units.stream().filter(u -> u.getUom().equals(s.uom())).findFirst().orElse(null);
            if (row == null) {
                units.add(new ProductUom(this, s.uom(), s.factor(), s.barcode()));
            } else {
                row.set(s.factor(), s.barcode());
            }
        }
    }

    public void archive() {
        this.deletedAt = Instant.now();
    }

    public void setImageFileId(Long imageFileId) {
        this.imageFileId = imageFileId;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getSku() {
        return sku;
    }

    public String getBarcode() {
        return barcode;
    }

    public String getName() {
        return name;
    }

    public String getBaseUom() {
        return baseUom;
    }

    public Integer getWeightG() {
        return weightG;
    }

    public Integer getVolumeCm3() {
        return volumeCm3;
    }

    public boolean isTrackBatch() {
        return trackBatch;
    }

    public boolean isTrackExpiry() {
        return trackExpiry;
    }

    public Long getCostMinor() {
        return costMinor;
    }

    public java.math.BigDecimal getReorderPoint() {
        return reorderPoint;
    }

    public String getCategory() {
        return category;
    }

    public String getAbcClass() {
        return abcClass;
    }

    public Long getImageFileId() {
        return imageFileId;
    }

    public List<ProductUom> getUnits() {
        return units;
    }

    public int getVersion() {
        return version;
    }
}
