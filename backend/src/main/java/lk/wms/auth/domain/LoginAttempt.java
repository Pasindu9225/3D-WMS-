package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** SEC-06: one row per login attempt, for the per-IP limit. The email is stored hashed. */
@Entity
@Table(name = "login_attempt")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 45)
    private String ip;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "email_hash", length = 64, nullable = false)
    private String emailHash;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoginAttempt() {
    }

    public LoginAttempt(String ip, String emailHash, boolean success) {
        this.ip = ip;
        this.emailHash = emailHash;
        this.success = success;
        this.createdAt = Instant.now();
    }
}
