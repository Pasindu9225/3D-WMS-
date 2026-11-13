package lk.wms.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.UserSession;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.repo.UserSessionRepository;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SEC-05: every request's "sid" must be a live session of an ACTIVE user. Cached 60 s so a request
 * costs no DB hit; revocations on this server evict the entry at once.
 * ponytail: single-server cache; with several app servers a revoked session lives up to 60 s elsewhere.
 */
@Service
public class SessionService {

    private final Cache<String, Optional<CurrentUser>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    private final UserSessionRepository sessions;
    private final AppUserRepository users;
    private final TransactionTemplate readTx;

    public SessionService(UserSessionRepository sessions, AppUserRepository users, PlatformTransactionManager tm) {
        this.sessions = sessions;
        this.users = users;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    public Optional<CurrentUser> verify(String sessionId) {
        return cache.get(sessionId, this::load);
    }

    public void evict(Collection<String> sessionIds) {
        cache.invalidateAll(sessionIds);
    }

    private Optional<CurrentUser> load(String sessionId) {
        // The JWT is verified but the tenant is not set yet, so look the session up across tenants.
        return TenantContext.callAsSystem(() -> readTx.execute(s -> sessions.findWithUser(sessionId)
                .filter(UserSession::isActive)
                .filter(session -> session.getUser().getStatus() == AppUser.Status.ACTIVE)
                .map(this::toCurrentUser)));
    }

    /** Must run inside a transaction. Roles and permissions are read fresh from the DB. */
    CurrentUser toCurrentUser(UserSession session) {
        AppUser user = session.getUser();
        return new CurrentUser(user.getId(), user.getPublicId(), session.getTenantId(), session.getId(),
                users.findRoleCodes(user.getId()),
                new HashSet<>(users.findPermissionCodes(user.getId())));
    }
}
