package lk.wms.auth.config;

import lk.wms.auth.domain.AppUser;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.service.UserService;
import lk.wms.common.tenant.TenantContext;
import lk.wms.tenant.service.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates the first company and its admin, once:
 * {@code java -jar backend.jar --wms.bootstrap.tenant="Acme Traders" --wms.bootstrap.admin-email=owner@acme.lk
 * --wms.bootstrap.admin-name="Owner Name"}. Prints a one-time invite link to the console (not the log file).
 * Refuses if that email already has an account, so running it twice does nothing.
 */
@Component
@ConditionalOnProperty("wms.bootstrap.admin-email")
public class BootstrapRunner implements ApplicationRunner {

    private final TenantService tenants;
    private final UserService users;
    private final AppUserRepository appUsers;
    private final String tenantName;
    private final String adminEmail;
    private final String adminName;

    public BootstrapRunner(TenantService tenants, UserService users, AppUserRepository appUsers,
            @Value("${wms.bootstrap.tenant}") String tenantName,
            @Value("${wms.bootstrap.admin-email}") String adminEmail,
            @Value("${wms.bootstrap.admin-name:Administrator}") String adminName) {
        this.tenants = tenants;
        this.users = users;
        this.appUsers = appUsers;
        this.tenantName = tenantName;
        this.adminEmail = adminEmail;
        this.adminName = adminName;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = AppUser.normalizeEmail(adminEmail);
        if (TenantContext.callAsSystem(() -> appUsers.findByEmail(email)).isPresent()) {
            System.out.println("[bootstrap] " + email + " already has an account; nothing created.");
            return;
        }
        long tenantId = tenants.create(tenantName.trim());
        UserService.Invite invite = TenantContext.callAs(tenantId,
                () -> users.createFirstAdmin(tenantId, email, adminName.trim()));
        System.out.println("[bootstrap] Created company '" + tenantName.trim() + "' and admin " + email + ".");
        System.out.println("[bootstrap] Open this link within 72 hours to set the admin password (shown once):");
        System.out.println("[bootstrap] " + invite.inviteUrl());
    }
}
