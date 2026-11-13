package lk.wms.common.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every tenant table (SEC-24). Hibernate adds {@code tenant_id = ?} to every query
 * and sets tenant_id on insert from {@link TenantContext}; code never sets it by hand.
 * Subclasses declare their own @Id (most are BIGINT, user_session is a ULID).
 */
@MappedSuperclass
public abstract class TenantScoped {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    public Long getTenantId() {
        return tenantId;
    }
}
