package lk.wms.tenant.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A customer company. Not TenantScoped: it IS the tenant. */
@Entity
@Table(name = "tenant")
public class Tenant {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "plan_code", nullable = false, length = 30)
    private String planCode;

    @Column(name = "max_warehouses", nullable = false)
    private int maxWarehouses;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "adjust_approval_minor", nullable = false)
    private long adjustApprovalMinor = 1_000_000;

    /** The one company whose users run the platform (Super Admin); never suspended. */
    @Column(nullable = false, updatable = false)
    private boolean platform;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_reason", length = 200)
    private String suspendedReason;

    @jakarta.persistence.Version
    private int version;

    protected Tenant() {
    }

    public Tenant(String name, String planCode, int maxWarehouses) {
        this.publicId = Ulid.next();
        this.name = name;
        this.planCode = planCode;
        this.maxWarehouses = maxWarehouses;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    /** The platform company (Super Admin). */
    public static Tenant platform(String name) {
        Tenant t = new Tenant(name, "PLATFORM", 0);
        t.platform = true;
        return t;
    }

    public void setPlan(String planCode, int maxWarehouses) {
        this.planCode = planCode;
        this.maxWarehouses = maxWarehouses;
    }

    public void suspend(String reason) {
        this.status = Status.SUSPENDED;
        this.suspendedAt = Instant.now();
        this.suspendedReason = reason;
    }

    public void reactivate() {
        this.status = Status.ACTIVE;
        this.suspendedAt = null;
        this.suspendedReason = null;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getName() {
        return name;
    }

    public String getPlanCode() {
        return planCode;
    }

    public int getMaxWarehouses() {
        return maxWarehouses;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPlatform() {
        return platform;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public String getSuspendedReason() {
        return suspendedReason;
    }

    public int getVersion() {
        return version;
    }
}
