package lk.wms.tenant.service;

import lk.wms.tenant.domain.Tenant;
import lk.wms.tenant.repo.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Companies. ponytail: only creation for now; plans, suspension and limits come with v0.2 / v2.0. */
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
}
