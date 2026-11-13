package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One login on one device. Its ID is the JWT "sid" claim (SEC-05). */
@Entity
@Table(name = "user_session")
public class UserSession extends TenantScoped {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 26)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserSession() {
    }

    public UserSession(AppUser user, String ip, String userAgent) {
        this.id = Ulid.next();
        this.user = user;
        this.ip = ip;
        this.userAgent = userAgent;
        this.createdAt = Instant.now();
        this.lastSeenAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public boolean isOlderThan(java.time.Duration maxAge, Instant now) {
        return createdAt.plus(maxAge).isBefore(now);
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void touch(Instant now) {
        lastSeenAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
