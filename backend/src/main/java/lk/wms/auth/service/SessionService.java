package lk.wms.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import lk.wms.auth.repo.RefreshTokenRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.UserSession;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.repo.UserSessionRepository;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.tenant.service.TenantService;
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
    private final RefreshTokenRepository refreshTokens;
    private final AppUserRepository users;
    private final TenantService tenants;
    private final TransactionTemplate readTx;

    public SessionService(UserSessionRepository sessions, RefreshTokenRepository refreshTokens,
            AppUserRepository users, TenantService tenants, PlatformTransactionManager tm) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tenants = tenants;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    public Optional<CurrentUser> verify(String sessionId) {
        return cache.get(sessionId, this::load);
    }

    public void evict(Collection<String> sessionIds) {
        cache.invalidateAll(sessionIds);
    }

    /**
     * Evicts once the current transaction commits, so a parallel request cannot re-cache the old
     * state in between. Outside a transaction it evicts at once.
     */
    public void evictAfterCommit(Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(sessionIds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(ids);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(ids);
            }
        });
    }

    /** Must run in a transaction in the user's tenant. Revokes sessions and their refresh tokens. */
    public List<String> revokeAll(long userId, String keepSessionId) {
        Instant now = Instant.now();
        List<String> ids = new ArrayList<>();
        for (UserSession s : sessions.findActiveByUserId(userId)) {
            if (!s.getId().equals(keepSessionId)) {
                s.revoke(now);
                refreshTokens.revokeBySession(s.getId(), now);
                ids.add(s.getId());
            }
        }
        evictAfterCommit(ids);
        return ids;
    }

    /** Company suspended (v2.0): every session of the current company ends now. Run in that company's tenant. */
    public int revokeCompany() {
        Instant now = Instant.now();
        List<String> ids = new ArrayList<>();
        for (UserSession s : sessions.findActive()) {
            s.revoke(now);
            refreshTokens.revokeBySession(s.getId(), now);
            ids.add(s.getId());
        }
        evictAfterCommit(ids);
        return ids.size();
    }

    /** Last time anyone of the current company used the app, or null. */
    public Instant lastSeen() {
        return sessions.lastSeen();
    }

    /** Role change: keep the sessions, but drop their cached permissions so the change applies now. */
    public void refreshPermissions(long userId) {
        evictAfterCommit(sessions.findActiveByUserId(userId).stream().map(UserSession::getId).toList());
    }

    private Optional<CurrentUser> load(String sessionId) {
        // The JWT is verified but the tenant is not set yet, so look the session up across tenants.
        return TenantContext.callAsSystem(() -> readTx.execute(s -> sessions.findWithUser(sessionId)
                .filter(UserSession::isActive)
                .filter(session -> session.getUser().getStatus() == AppUser.Status.ACTIVE)
                .filter(session -> !session.isOlderThan(TokenService.SESSION_MAX_AGE, java.time.Instant.now()))
                .filter(session -> tenants.isActive(session.getTenantId())) // F8: suspension applies within 60 s
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
