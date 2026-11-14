package lk.wms.catalog.domain;

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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** A stored, re-encoded file (SEC-43/44). The bytes live on disk at {@code path}, never in the web root. */
@Entity
@Table(name = "file_object")
@SQLRestriction("deleted_at is null")
public class FileObject extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "content_type", nullable = false, length = 40, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(name = "width_px", nullable = false, updatable = false)
    private int widthPx;

    @Column(name = "height_px", nullable = false, updatable = false)
    private int heightPx;

    @Column(nullable = false, length = 200, updatable = false)
    private String path;

    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected FileObject() {
    }

    public FileObject(String contentType, int sizeBytes, int widthPx, int heightPx, String path, long createdBy) {
        this.publicId = Ulid.next();
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.path = path;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void delete() {
        this.deletedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getContentType() {
        return contentType;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public String getPath() {
        return path;
    }
}
