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

    /** Plan limit on warehouses (per-warehouse pricing). Locks the company row until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int lockMaxWarehouses(long tenantId) {
        return tenants.lockMaxWarehouses(tenantId).orElseThrow();
    }

    /** Stock adjustments worth more than this (cents) need a manager's approval (default LKR 10,000). */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public long adjustApprovalLimit(long tenantId) {
        return tenants.findAdjustApprovalMinor(tenantId).orElse(1_000_000L);
    }

    /** F8: a suspended (or unknown) company cannot sign in, refresh or use a live session. */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public boolean isActive(long tenantId) {
        return tenants.findStatus(tenantId).map(s -> s == Tenant.Status.ACTIVE).orElse(false);
    }
}
