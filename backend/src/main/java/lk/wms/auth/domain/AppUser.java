package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.status = status;
        this.createdAt = Instant.now();
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
}
