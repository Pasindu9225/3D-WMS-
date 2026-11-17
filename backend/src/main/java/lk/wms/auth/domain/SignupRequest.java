package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A sign-up waiting for its email to be confirmed (v2.0). Not TenantScoped: there is no company yet. */
@Entity
@Table(name = "signup_request")
public class SignupRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 190, updatable = false)
    private String email;

    @Column(name = "company_name", nullable = false, length = 150, updatable = false)
    private String companyName;

    @Column(name = "full_name", nullable = false, length = 120, updatable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false, updatable = false)
    private String passwordHash;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(length = 45, updatable = false)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected SignupRequest() {
    }

    public SignupRequest(String email, String companyName, String fullName, String passwordHash, String tokenHash, String ip,
            Instant expiresAt) {
        this.email = email;
        this.companyName = companyName;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.tokenHash = tokenHash;
        this.ip = ip;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void use(Instant now) {
        this.usedAt = now;
    }

    public String getEmail() {
        return email;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
