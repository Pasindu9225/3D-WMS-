package lk.wms.tenant.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lk.wms.common.errors.ApiException;
import lk.wms.tenant.domain.Plan;
import lk.wms.tenant.domain.Tenant;
import lk.wms.tenant.repo.PlanRepository;
import lk.wms.tenant.repo.TenantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Companies and plans as the Super Admin sees them (v2.0). Companies are not tenant-scoped rows, so these reads see
 * every company; the platform module decides who may call them (platform:read / platform:manage).
 */
@Service
public class PlatformTenants {

    public record Company(long id, String publicId, String name, String planCode, int maxWarehouses, String status,
            Instant createdAt, Instant suspendedAt, String suspendedReason, int version) {
    }

    public record PlanView(String code, String name, long pricePerWarehouseMinor, int defaultMaxWarehouses, boolean active,
            long companies, int version) {
    }

    private final TenantRepository tenants;
    private final PlanRepository plans;

    public PlatformTenants(TenantRepository tenants, PlanRepository plans) {
        this.tenants = tenants;
        this.plans = plans;
    }

    @Transactional(readOnly = true)
    public Page<Company> search(String q, String status, Pageable pageable) {
        Tenant.Status s = status == null || status.isBlank() ? null : Tenant.Status.valueOf(status);
        String like = q == null ? "" : q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return tenants.search(like, s, pageable).map(PlatformTenants::view);
    }

    /** A customer company by public id (never the platform company: 404). */
    @Transactional(readOnly = true)
    public Company get(String publicId) {
        return view(customer(tenants.findByPublicId(publicId).orElse(null)));
    }

    /** A new customer company on a plan, with that plan's warehouse limit. */
    @Transactional
    public Company create(String name, String planCode) {
        Plan plan = activePlan(planCode);
        return view(tenants.saveAndFlush(new Tenant(name.trim(), plan.getCode(), plan.getDefaultMaxWarehouses())));
    }

    /** The platform company, made once (bootstrap). */
    @Transactional
    public long platformId(String nameIfNew) {
        return tenants.findFirstByPlatformTrue().orElseGet(() -> tenants.saveAndFlush(Tenant.platform(nameIfNew))).getId();
    }

    @Transactional(readOnly = true)
    public boolean isPlatform(long tenantId) {
        return tenants.findFirstByPlatformTrue().map(t -> t.getId() == tenantId).orElse(false);
    }

    /** Plan and warehouse limit; the limit may not go below the warehouses in use (checked by the caller, who counts them). */
    @Transactional
    public Company setPlan(String publicId, int version, String planCode, int maxWarehouses, int inUse) {
        Tenant t = locked(publicId, version);
        Plan plan = activePlanOrSame(planCode, t.getPlanCode());
        if (maxWarehouses < Math.max(1, inUse)) {
            throw new ApiException(HttpStatus.CONFLICT, "LIMIT_BELOW_USE",
                    "This company already has " + inUse + " warehouse(s); the limit cannot be lower");
        }
        t.setPlan(plan.getCode(), maxWarehouses);
        tenants.flush();
        return view(t);
    }

    @Transactional
    public Company suspend(String publicId, int version, String reason) {
        Tenant t = locked(publicId, version);
        if (t.getStatus() == Tenant.Status.SUSPENDED) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUSPENDED", "This company is already suspended");
        }
        t.suspend(reason.trim());
        tenants.flush();
        return view(t);
    }

    @Transactional
    public Company reactivate(String publicId, int version) {
        Tenant t = locked(publicId, version);
        if (t.getStatus() == Tenant.Status.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_ACTIVE", "This company is already active");
        }
        t.reactivate();
        tenants.flush();
        return view(t);
    }

    // ---------------------------------------------------------------- plans

    @Transactional(readOnly = true)
    public List<PlanView> plans() {
        return plans.findAllByOrderByPricePerWarehouseMinorAscCodeAsc().stream().map(this::view).toList();
    }

    @Transactional
    public PlanView createPlan(String code, String name, long price, int defaultMax) {
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (plans.findByCode(c).isPresent() || "PLATFORM".equals(c)) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_EXISTS", "There is already a plan " + c);
        }
        return view(plans.saveAndFlush(new Plan(c, name.trim(), price, defaultMax)));
    }

    /** Edit price, default limit, name; switch off (companies on it keep it). Plans are never deleted. */
    @Transactional
    public PlanView updatePlan(String code, int version, String name, long price, int defaultMax, boolean active) {
        Plan p = plans.findByCode(code).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Plan not found"));
        if (p.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_CHANGED", "Someone else changed this plan; reload it");
        }
        p.set(name.trim(), price, defaultMax, active);
        plans.flush();
        return view(p);
    }

    // ---------------------------------------------------------------- helpers

    private Tenant customer(Tenant t) {
        if (t == null || t.isPlatform()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company not found");
        }
        return t;
    }

    private Tenant locked(String publicId, int version) {
        Tenant t = customer(tenants.lockByPublicId(publicId).orElse(null));
        if (t.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPANY_CHANGED", "Someone else changed this company; reload it");
        }
        return t;
    }

    private Plan activePlan(String code) {
        return plans.findByCode(code == null ? "" : code.trim().toUpperCase(Locale.ROOT)).filter(Plan::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "PLAN_NOT_FOUND", "There is no active plan " + code));
    }

    /** A company may stay on a plan that was switched off; moving to another plan needs an active one. */
    private Plan activePlanOrSame(String code, String current) {
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.equals(current) ? plans.findByCode(c).orElseGet(() -> activePlan(c)) : activePlan(c);
    }

    private static Company view(Tenant t) {
        return new Company(t.getId(), t.getPublicId(), t.getName(), t.getPlanCode(), t.getMaxWarehouses(), t.getStatus().name(),
                t.getCreatedAt(), t.getSuspendedAt(), t.getSuspendedReason(), t.getVersion());
    }

    private PlanView view(Plan p) {
        return new PlanView(p.getCode(), p.getName(), p.getPricePerWarehouseMinor(), p.getDefaultMaxWarehouses(), p.isActive(),
                tenants.countByPlanCode(p.getCode()), p.getVersion());
    }
}
