package lk.wms.tenant.service;

import lk.wms.tenant.domain.Tenant;
import lk.wms.tenant.repo.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Companies. ponytail: creation and status only; plans and the suspend screen come with v2.0. */
@Service
public class TenantService {

    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /** @return the new tenant's internal ID */
    @Transactional
    public long create(String name) {
        return tenants.save(new Tenant(name, "PILOT", 1)).getId();
    }

    /** F8: a suspended (or unknown) company cannot sign in, refresh or use a live session. */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public boolean isActive(long tenantId) {
        return tenants.findStatus(tenantId).map(s -> s == Tenant.Status.ACTIVE).orElse(false);
    }
}
