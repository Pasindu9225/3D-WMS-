package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One version of a warehouse layout: the DRAFT being edited, or a PUBLISHED one (task 3). */
@Entity
@Table(name = "layout_version")
public class LayoutVersion extends TenantScoped {

    public enum Status { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String layout;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected LayoutVersion() {
    }

    public static LayoutVersion newDraft(long warehouseId, String layoutJson, long userId) {
        LayoutVersion v = new LayoutVersion();
        v.warehouseId = warehouseId;
        v.status = Status.DRAFT;
        v.revision = 1;
        v.layout = layoutJson;
        v.createdBy = userId;
        v.createdAt = Instant.now();
        v.updatedBy = userId;
        v.updatedAt = v.createdAt;
        return v;
    }

    public void saveDraft(String layoutJson, long userId) {
        this.layout = layoutJson;
        this.revision++;
        this.updatedBy = userId;
        this.updatedAt = Instant.now();
    }

    public void markPublished(long userId) {
        this.status = Status.PUBLISHED;
        this.publishedBy = userId;
        this.publishedAt = Instant.now();
    }

    public Status getStatus() {
        return status;
    }

    public int getRevision() {
        return revision;
    }

    public String getLayout() {
        return layout;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
