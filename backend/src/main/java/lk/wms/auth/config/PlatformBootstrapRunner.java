package lk.wms.auth.config;

import lk.wms.auth.service.UserService;
import lk.wms.common.tenant.TenantContext;
import lk.wms.tenant.service.PlatformTenants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates the platform company and a Super Admin, once (v2.0):
 * {@code java -jar backend.jar --wms.bootstrap.platform-admin-email=you@example.lk --wms.bootstrap.platform-admin-name="Your Name"}.
 * Prints a one-time invite link to the console (not the log file). Refuses if that email already has an account.
 */
@Component
@ConditionalOnProperty("wms.bootstrap.platform-admin-email")
public class PlatformBootstrapRunner implements ApplicationRunner {

    private final PlatformTenants tenants;
    private final UserService users;
    private final String email;
    private final String name;

    public PlatformBootstrapRunner(PlatformTenants tenants, UserService users,
            @Value("${wms.bootstrap.platform-admin-email}") String email,
            @Value("${wms.bootstrap.platform-admin-name:Super Admin}") String name) {
        this.tenants = tenants;
        this.users = users;
        this.email = email;
        this.name = name;
    }

    @Override
    public void run(ApplicationArguments args) {
        String normalized = lk.wms.auth.domain.AppUser.normalizeEmail(email);
        if (!users.emailFree(normalized)) {
            System.out.println("[bootstrap] " + normalized + " already has an account; nothing created.");
            return;
        }
        long platformId = TenantContext.callAsSystem(() -> tenants.platformId("Platform"));
        UserService.Invite invite = TenantContext.callAs(platformId, () -> users.createPlatformAdmin(platformId, normalized, name.trim()));
        System.out.println("[bootstrap] Created Super Admin " + normalized + " in the platform company.");
        System.out.println("[bootstrap] Open this link within 72 hours to set the password (shown once):");
        System.out.println("[bootstrap] " + invite.inviteUrl());
    }
}
