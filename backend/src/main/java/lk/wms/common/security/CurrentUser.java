package lk.wms.common.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The logged-in user of this request, built from a verified session (SEC-05).
 * Any module may read it; IDs are internal and must not be sent to clients.
 */
public record CurrentUser(
        long userId,
        String userPublicId,
        long tenantId,
        String sessionId,
        List<String> roles,
        Set<String> permissions) {

    /** Tenant admins see every warehouse (SEC-10); everyone else only assigned ones. */
    public boolean isTenantAdmin() {
        return roles.contains("TENANT_ADMIN");
    }

    public static Optional<CurrentUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CurrentUser u ? Optional.of(u) : Optional.empty();
    }

    public static CurrentUser require() {
        return get().orElseThrow(() -> new IllegalStateException("no authenticated user"));
    }
}
