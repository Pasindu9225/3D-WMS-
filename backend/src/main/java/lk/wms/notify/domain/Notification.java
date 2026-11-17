package lk.wms.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One alert for one person (v1.2). The text is made in the app from kind + params. */
@Entity
@Table(name = "notification")
public class Notification extends TenantScoped {

    public enum Kind { LOW_STOCK, TASK_ASSIGNED, EXPIRY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String params;

    @Column(length = 200, updatable = false)
    private String link;

    @Column(length = 100, nullable = false, updatable = false)
    private String dedupe;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(long userId, Kind kind, String paramsJson, String link, String dedupe) {
        this.publicId = Ulid.next();
        this.userId = userId;
        this.kind = kind;
        this.params = paramsJson;
        this.link = link;
        this.dedupe = dedupe;
        this.createdAt = Instant.now();
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getUserId() {
        return userId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getParams() {
        return params;
    }

    public String getLink() {
        return link;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
