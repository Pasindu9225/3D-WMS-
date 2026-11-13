package lk.wms.auth.service;

import java.time.Duration;
import java.time.Instant;
import lk.wms.auth.repo.LoginAttemptRepository;
import lk.wms.auth.repo.RefreshTokenRepository;
import lk.wms.auth.repo.UserInviteRepository;
import lk.wms.auth.repo.UserSessionRepository;
import lk.wms.common.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F6 (security review): nightly delete of auth rows nobody can use any more. Keeps tables small and
 * drops old client IPs (personal data) from login_attempt. The audit log is never touched (append-only).
 */
@Component
public class CleanupJob {

    static final Duration RETENTION = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    public record Result(int loginAttempts, int refreshTokens, int sessions, int invites) {
    }

    private final LoginAttemptRepository attempts;
    private final RefreshTokenRepository refreshTokens;
    private final UserSessionRepository sessions;
    private final UserInviteRepository invites;
    private final TransactionTemplate tx;

    public CleanupJob(LoginAttemptRepository attempts, RefreshTokenRepository refreshTokens,
            UserSessionRepository sessions, UserInviteRepository invites, PlatformTransactionManager tm) {
        this.attempts = attempts;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.invites = invites;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(cron = "0 30 3 * * *") // 03:30 server time, when warehouses are quiet
    public void nightly() {
        Result r = run(Instant.now());
        log.info("Cleanup removed {} login attempts, {} refresh tokens, {} sessions, {} invites",
                r.loginAttempts(), r.refreshTokens(), r.sessions(), r.invites());
    }

    /** Runs across all companies (system context), in one transaction. */
    public Result run(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        return TenantContext.callAsSystem(() -> tx.execute(status -> {
            int a = attempts.deleteOlderThan(cutoff);
            int t = refreshTokens.deleteExpiredBefore(cutoff) + refreshTokens.deleteForOldSessions(cutoff);
            int s = sessions.deleteOlderThan(cutoff);
            int i = invites.deleteExpiredBefore(cutoff);
            return new Result(a, t, s, i);
        }));
    }
}
