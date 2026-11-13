package lk.wms.auth.domain;

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
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_user")
public class AppUser extends TenantScoped {

    public enum Status { INVITED, ACTIVE, DISABLED, LOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 190)
    private String email;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    /** SEC-25: never leaves the auth module; no DTO has this field. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Version
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AppUser() {
    }

    public AppUser(String email, String fullName, String passwordHash, Status status) {
        this.publicId = Ulid.next();
        this.email = normalizeEmail(email);
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Internal ID: never put it in an API response; use publicId. */
    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Status getStatus() {
        return status;
    }

    public int getFailedLogins() {
        return failedLogins;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void rename(String fullName) {
        this.fullName = fullName;
        this.updatedAt = Instant.now();
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * SEC-06: every 5th failure locks the account: 15 min, then 30, 60 ... capped at 24 h.
     * Attempts made while locked are rejected before this is called, so they do not extend the lock.
     */
    public void recordFailedLogin(Instant now) {
        failedLogins++;
        if (failedLogins % 5 == 0) {
            long minutes = Math.min(15L << Math.min(failedLogins / 5 - 1, 10), 24 * 60);
            lockedUntil = now.plus(java.time.Duration.ofMinutes(minutes));
        }
        updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        if (failedLogins != 0 || lockedUntil != null) {
            failedLogins = 0;
            lockedUntil = null;
            updatedAt = now;
        }
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.updatedAt = Instant.now();
    }

    /** The invited user set their password through the invite link. */
    public void acceptInvite(String newHash) {
        this.passwordHash = newHash;
        this.status = Status.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.status = Status.DISABLED;
        this.updatedAt = Instant.now();
    }

    /** Back to ACTIVE, or to INVITED if they never set a password. Also clears any lockout. */
    public void enable(boolean hasSetPassword) {
        this.status = hasSetPassword ? Status.ACTIVE : Status.INVITED;
        this.failedLogins = 0;
        this.lockedUntil = null;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
