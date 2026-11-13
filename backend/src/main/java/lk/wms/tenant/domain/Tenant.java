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
}
