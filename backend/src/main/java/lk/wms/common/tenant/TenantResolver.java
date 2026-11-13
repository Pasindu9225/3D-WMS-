package lk.wms.common.tenant;

import java.util.Map;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/** Feeds {@link TenantContext} to Hibernate so every {@code @TenantId} entity is filtered and stamped. */
@Component
public class TenantResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        return TenantContext.current();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return TenantContext.SYSTEM.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> properties) {
        properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
