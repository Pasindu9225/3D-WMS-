package lk.wms.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.LoginAttempt;
import lk.wms.auth.domain.RefreshToken;
import lk.wms.auth.domain.UserSession;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.repo.LoginAttemptRepository;
import lk.wms.auth.repo.RefreshTokenRepository;
import lk.wms.auth.repo.UserSessionRepository;
import lk.wms.common.Ulid;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login, refresh, logout (SEC-01..08, SEC-29). The tenant is unknown until the user is found, so
 * lookups run in system context and every write to tenant data runs in the user's tenant.
 * TenantContext is always set BEFORE the transaction starts (Hibernate reads it when the session opens).
 */
@Service
public class AuthService {

    static final int IP_MAX_FAILURES = 20;          // per 15 min; higher than per-account: offices share one IP
    static final Duration IP_WINDOW = Duration.ofMinutes(15);

    public record Me(String id, String email, String fullName, List<String> roles, List<String> permissions) {
    }

    public record LoginResult(String accessToken, String refreshToken, Me me) {
    }

    private enum Outcome { SUCCESS, BAD_CREDENTIALS, LOCKED, INACTIVE }

    private final AppUserRepository users;
    private final UserSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final LoginAttemptRepository attempts;
    private final SessionService sessionService;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final JwtDecoder jwtDecoder;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final String dummyHash;

    public AuthService(AppUserRepository users, UserSessionRepository sessions, RefreshTokenRepository refreshTokens,
            LoginAttemptRepository attempts, SessionService sessionService, TokenService tokens,
            PasswordEncoder passwords, JwtDecoder jwtDecoder, AuditService audit, PlatformTransactionManager tm) {
        this.users = users;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.attempts = attempts;
        this.sessionService = sessionService;
        this.tokens = tokens;
        this.passwords = passwords;
        this.jwtDecoder = jwtDecoder;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.dummyHash = passwords.encode("timing-equaliser-not-a-real-password");
    }

    // ---------------------------------------------------------------- login

    public LoginResult login(String email, String password, String ip, String userAgent) {
        String normalized = AppUser.normalizeEmail(email);
        String emailHash = TokenService.sha256(normalized);

        record Lookup(boolean ipBlocked, AppUser user) {
        }
        Lookup lookup = inSystemTx(() -> new Lookup(
                attempts.countFailuresFromIp(ip, Instant.now().minus(IP_WINDOW)) >= IP_MAX_FAILURES,
                users.findByEmail(normalized).orElse(null)));

        if (lookup.ipBlocked() || lookup.user() == null) {
            passwords.matches(password, dummyHash); // same work as a real check: no timing hint (SEC-07)
            inSystemTx(() -> {
                attempts.save(new LoginAttempt(ip, emailHash, false));
                audit.record(null, null, lookup.ipBlocked() ? "LOGIN_BLOCKED_IP" : "LOGIN_FAILED", "app_user", null);
                return null;
            });
            throw authFailed();
        }

        long tenantId = lookup.user().getTenantId();
        String userPublicId = lookup.user().getPublicId();
        record Step(Outcome outcome, LoginResult result) {
        }
        Step step = inTenantTx(tenantId, () -> {
            Instant now = Instant.now();
            AppUser user = users.lockByPublicId(userPublicId).orElseThrow();
            Outcome o;
            LoginResult ok = null;
            if (user.isLocked(now)) {
                passwords.matches(password, dummyHash);
                o = Outcome.LOCKED;
            } else if (!passwords.matches(password, user.getPasswordHash())) {
                user.recordFailedLogin(now);
                o = Outcome.BAD_CREDENTIALS;
            } else if (user.getStatus() != AppUser.Status.ACTIVE) {
                o = Outcome.INACTIVE; // checked after the password so status never leaks to strangers
            } else {
                o = Outcome.SUCCESS;
                user.recordSuccessfulLogin(now);
                if (passwords.upgradeEncoding(user.getPasswordHash())) {
                    user.changePassword(passwords.encode(password));
                }
                UserSession session = sessions.save(new UserSession(user, ip, userAgent));
                String refresh = tokens.newRefreshToken();
                refreshTokens.save(new RefreshToken(session, Ulid.next(), TokenService.sha256(refresh),
                        now.plus(TokenService.REFRESH_TTL)));
                CurrentUser cu = sessionService.toCurrentUser(session);
                ok = new LoginResult(tokens.issueAccessToken(cu, now), refresh, me(user, cu));
            }
            attempts.save(new LoginAttempt(ip, emailHash, o == Outcome.SUCCESS));
            audit.record(tenantId, user.getId(), "LOGIN_" + o.name(), "app_user", user.getPublicId());
            return new Step(o, ok);
        });
        if (step.outcome() != Outcome.SUCCESS) {
            throw authFailed(); // committed above: the failed-login count is kept
        }
        return step.result();
    }

    // ---------------------------------------------------------------- refresh (SEC-03, SEC-04)

    public LoginResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank() || rawRefreshToken.length() > 100) {
            throw authFailed();
        }
        String hash = TokenService.sha256(rawRefreshToken);
        List<String> evict = new ArrayList<>();
        LoginResult result = inSystemTx(() -> {
            Instant now = Instant.now();
            RefreshToken token = refreshTokens.lockByHash(hash).orElse(null);
            if (token == null) {
                return null;
            }
            UserSession session = token.getSession();
            AppUser user = session.getUser();
            if (token.isUsed() && !token.isRevoked()) {
                // A rotated-out token came back: someone has a copy. Kill the whole family.
                refreshTokens.revokeFamily(token.getFamilyId(), now);
                session.revoke(now);
                evict.add(session.getId());
                audit.record(session.getTenantId(), user.getId(), "TOKEN_REUSE", "user_session", session.getId());
                return null;
            }
            if (token.isRevoked() || token.isExpired(now) || !session.isActive()) {
                return null;
            }
            if (user.getStatus() != AppUser.Status.ACTIVE) {
                refreshTokens.revokeBySession(session.getId(), now);
                session.revoke(now);
                evict.add(session.getId());
                return null;
            }
            token.markUsed(now);
            session.touch(now);
            String next = tokens.newRefreshToken();
            refreshTokens.save(new RefreshToken(session, token.getFamilyId(), TokenService.sha256(next),
                    now.plus(TokenService.REFRESH_TTL)));
            CurrentUser cu = sessionService.toCurrentUser(session);
            return new LoginResult(tokens.issueAccessToken(cu, now), next, me(user, cu));
        });
        sessionService.evict(evict);
        if (result == null) {
            throw authFailed();
        }
        return result;
    }

    // ---------------------------------------------------------------- logout (SEC-08)

    /** Revokes the session named by the refresh cookie and/or the access cookie. Never fails. */
    public void logout(String rawRefreshToken, String accessToken) {
        String accessSid = sessionIdFrom(accessToken);
        String refreshHash = rawRefreshToken == null || rawRefreshToken.length() > 100
                ? null : TokenService.sha256(rawRefreshToken);
        List<String> revoked = inSystemTx(() -> {
            Instant now = Instant.now();
            List<UserSession> targets = new ArrayList<>();
            if (refreshHash != null) {
                refreshTokens.lockByHash(refreshHash).map(RefreshToken::getSession).ifPresent(targets::add);
            }
            if (accessSid != null) {
                sessions.findWithUser(accessSid).ifPresent(targets::add);
            }
            List<String> ids = new ArrayList<>();
            for (UserSession s : targets) {
                if (s.isActive()) {
                    s.revoke(now);
                    refreshTokens.revokeBySession(s.getId(), now);
                    audit.record(s.getTenantId(), s.getUser().getId(), "LOGOUT", "user_session", s.getId());
                    ids.add(s.getId());
                }
            }
            return ids;
        });
        sessionService.evict(revoked);
    }

    public void logoutAll(CurrentUser cu) {
        List<String> revoked = inTenantTx(cu.tenantId(), () -> revokeSessions(cu, null, "LOGOUT_ALL"));
        sessionService.evict(revoked);
    }

    // ---------------------------------------------------------------- me / password

    public Me me(CurrentUser cu) {
        return inTenantTx(cu.tenantId(), () -> me(users.findByPublicId(cu.userPublicId()).orElseThrow(), cu));
    }

    /** Wrong current password counts as a failed login, so this cannot be used to brute-force it. */
    public void changePassword(CurrentUser cu, String currentPassword, String newPassword) {
        record Result(String error, List<String> revoked) {
        }
        Result r = inTenantTx(cu.tenantId(), () -> {
            AppUser user = users.lockByPublicId(cu.userPublicId()).orElseThrow();
            if (user.isLocked(Instant.now()) || !passwords.matches(currentPassword, user.getPasswordHash())) {
                if (!user.isLocked(Instant.now())) {
                    user.recordFailedLogin(Instant.now());
                }
                audit.record(cu.tenantId(), cu.userId(), "PASSWORD_CHANGE_FAILED", "app_user", user.getPublicId());
                return new Result("PASSWORD_INCORRECT", List.of());
            }
            if (passwords.matches(newPassword, user.getPasswordHash())) {
                return new Result("PASSWORD_UNCHANGED", List.of());
            }
            user.changePassword(passwords.encode(newPassword));
            return new Result(null, revokeSessions(cu, cu.sessionId(), "PASSWORD_CHANGED"));
        });
        sessionService.evict(r.revoked());
        if ("PASSWORD_INCORRECT".equals(r.error())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, r.error(), "Current password is incorrect");
        }
        if (r.error() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, r.error(), "New password must be different");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Revokes every active session of the user except {@code keepSessionId}; must run in a tenant tx. */
    private List<String> revokeSessions(CurrentUser cu, String keepSessionId, String action) {
        List<String> ids = sessionService.revokeAll(cu.userId(), keepSessionId);
        audit.record(cu.tenantId(), cu.userId(), action, "app_user", cu.userPublicId());
        return ids;
    }

    private String sessionIdFrom(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            return jwtDecoder.decode(accessToken).getClaimAsString("sid");
        } catch (JwtException e) {
            return null; // expired or forged: nothing to revoke through it
        }
    }

    private static Me me(AppUser user, CurrentUser cu) {
        return new Me(user.getPublicId(), user.getEmail(), user.getFullName(),
                cu.roles(), cu.permissions().stream().sorted().toList());
    }

    /** SEC-07: one message for every failure: unknown email, wrong password, locked, disabled. */
    private static ApiException authFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_FAILED", "Authentication failed");
    }

    private <T> T inSystemTx(Supplier<T> work) {
        return TenantContext.callAsSystem(() -> tx.execute(status -> work.get()));
    }

    private <T> T inTenantTx(long tenantId, Supplier<T> work) {
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> work.get()));
    }
}
