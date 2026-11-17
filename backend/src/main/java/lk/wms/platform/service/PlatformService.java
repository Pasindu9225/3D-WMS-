package lk.wms.platform.service;

import java.time.Instant;
import java.util.Map;
import lk.wms.auth.service.SessionService;
import lk.wms.auth.service.UserService;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.tenant.service.PlatformTenants;
import lk.wms.tenant.service.PlatformTenants.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Super Admin portal (v2.0 task 1). Shows companies with counts only — never their products, stock, orders or
 * people — and changes plan, limit and status. Work inside a company runs in that company's own context
 * ({@link TenantContext#callAs}), one transaction per step, so the company filter applies to every query. Each action
 * is audited twice: in the platform company's log and in the target company's log.
 */
@Service
public class PlatformService {

    public record Row(String id, String name, String planCode, int maxWarehouses, String status, Instant createdAt,
            int warehouses, long users, int version) {
    }

    public record Usage(int warehouses, long activeUsers, long products, long movements30Days, long storedBytes, Instant lastActive) {
    }

    public record Detail(String id, String name, String planCode, int maxWarehouses, String status, Instant createdAt,
            Instant suspendedAt, String suspendedReason, Usage usage, int version) {
    }

    public record Created(Detail company, String inviteUrl, Instant inviteExpiresAt) {
    }

    private final PlatformTenants tenants;
    private final UserService users;
    private final SessionService sessions;
    private final WarehouseService warehouses;
    private final ProductService products;
    private final StockService stock;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PlatformService(PlatformTenants tenants, UserService users, SessionService sessions, WarehouseService warehouses,
            ProductService products, StockService stock, AuditService audit, PlatformTransactionManager tm) {
        this.tenants = tenants;
        this.users = users;
        this.sessions = sessions;
        this.warehouses = warehouses;
        this.products = products;
        this.stock = stock;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    /** ponytail: two small counts per company on the page (25 by default); a usage table if there are thousands. */
    public Page<Row> list(String q, String status, Pageable pageable) {
        return tenants.search(q, status, pageable).map(c -> TenantContext.callAs(c.id(), () -> new Row(c.publicId(), c.name(), c.planCode(),
                c.maxWarehouses(), c.status(), c.createdAt(), warehouses.liveIds().size(), users.activeCount(), c.version())));
    }

    public Detail get(String publicId) {
        return detail(tenants.get(publicId));
    }

    /** A company you sign up yourself: the company on a plan, and its first admin's invite link (shown once). */
    public Created create(CurrentUser cu, String name, String planCode, String adminEmail, String adminName) {
        if (!users.emailFree(adminEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "That email already has an account");
        }
        Company c = tenants.create(name, planCode);
        // ponytail: two steps (company, then admin) in two transactions, each in its own company; the email was checked first
        UserService.Invite invite = TenantContext.callAs(c.id(), () -> users.createFirstAdmin(c.id(), adminEmail.trim(), adminName.trim()));
        log(cu, c, "COMPANY_CREATED", null, Map.of("name", c.name(), "plan", c.planCode(), "admin", adminEmail.trim()));
        return new Created(detail(c), invite.inviteUrl(), invite.expiresAt());
    }

    public Detail setPlan(CurrentUser cu, String publicId, int version, String planCode, int maxWarehouses) {
        Company before = tenants.get(publicId);
        int inUse = TenantContext.callAs(before.id(), () -> warehouses.liveIds().size());
        Company after = tenants.setPlan(publicId, version, planCode, maxWarehouses, inUse);
        log(cu, after, "COMPANY_PLAN_CHANGED", Map.of("plan", before.planCode(), "maxWarehouses", before.maxWarehouses()),
                Map.of("plan", after.planCode(), "maxWarehouses", after.maxWarehouses()));
        return detail(after);
    }

    /** Suspended: nobody of that company can sign in, and everyone signed in is signed out now. */
    public Detail suspend(CurrentUser cu, String publicId, int version, String reason) {
        Company c = tenants.suspend(publicId, version, reason);
        int ended = TenantContext.callAs(c.id(), () -> tx.execute(s -> sessions.revokeCompany()));
        log(cu, c, "COMPANY_SUSPENDED", null, Map.of("reason", c.suspendedReason(), "sessionsEnded", ended));
        return detail(c);
    }

    public Detail reactivate(CurrentUser cu, String publicId, int version) {
        Company c = tenants.reactivate(publicId, version);
        log(cu, c, "COMPANY_REACTIVATED", null, null);
        return detail(c);
    }

    private Detail detail(Company c) {
        Usage u = TenantContext.callAs(c.id(), () -> {
            long[] catalog = products.usage();
            return new Usage(warehouses.liveIds().size(), users.activeCount(), catalog[0], stock.movementsLast30Days(), catalog[1],
                    tx.execute(s -> sessions.lastSeen()));
        });
        return new Detail(c.publicId(), c.name(), c.planCode(), c.maxWarehouses(), c.status(), c.createdAt(), c.suspendedAt(),
                c.suspendedReason(), u, c.version());
    }

    /** In the Super Admin's own (platform) log and in the company's log. */
    private void log(CurrentUser cu, Company c, String action, Object before, Object after) {
        tx.executeWithoutResult(s -> audit.record(cu.tenantId(), cu.userId(), action, "tenant", c.publicId(), before, after));
        TenantContext.callAs(c.id(), () -> {
            tx.executeWithoutResult(s -> audit.record(c.id(), cu.userId(), action, "tenant", c.publicId(), before, after));
            return null;
        });
    }
}
