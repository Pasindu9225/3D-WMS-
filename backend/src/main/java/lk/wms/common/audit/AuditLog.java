package lk.wms.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only (SEC-28; DB triggers block UPDATE/DELETE). Not TenantScoped: system events such as a
 * failed login for an unknown email have no tenant. Reads must filter by tenant explicitly (task 6).
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 60)
    private String entity;

    @Column(name = "entity_id", length = 40)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json")
    private String afterJson;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "correlation_id", length = 26)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    AuditLog(Long tenantId, Long userId, String action, String entity, String entityId,
            String beforeJson, String afterJson, String ip, String userAgent, String correlationId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.ip = ip;
        this.userAgent = userAgent;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    AuditService.Entry toEntry() {
        return new AuditService.Entry(id, userId, action, entity, entityId, beforeJson, afterJson, ip, correlationId,
                createdAt);
    }
}
