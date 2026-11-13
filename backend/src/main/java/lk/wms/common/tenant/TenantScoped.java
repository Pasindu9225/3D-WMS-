package lk.wms.common.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every tenant table (SEC-24). Hibernate adds {@code tenant_id = ?} to every query
 * and sets tenant_id on insert from {@link TenantContext}; code never sets it by hand.
 */
@MappedSuperclass
public abstract class TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Internal ID: never put it in an API response; use publicId. */
    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }
}
