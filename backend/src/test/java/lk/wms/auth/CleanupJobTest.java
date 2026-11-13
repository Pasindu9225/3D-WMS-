package lk.wms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import lk.wms.IntegrationTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.service.CleanupJob;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** F6: the nightly cleanup removes only rows nobody can use any more. */
class CleanupJobTest extends IntegrationTest {

    @Autowired
    CleanupJob job;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppUserRepository users;

    @Test
    void removesOldRowsAndKeepsRecentOnes() {
        long tenant = newTenant();
        AppUser user = TenantContext.callAs(tenant, () -> users.save(
                new AppUser("c-" + Ulid.next() + "@t.lk", "C", "x", AppUser.Status.ACTIVE)));
        String oldSession = session(tenant, user.getId(), "NOW(3) - INTERVAL 40 DAY");
        String liveSession = session(tenant, user.getId(), "NOW(3)");
        token(oldSession, "NOW(3) - INTERVAL 33 DAY");
        token(liveSession, "NOW(3) + INTERVAL 7 DAY");
        attempt("NOW(3) - INTERVAL 31 DAY");
        attempt("NOW(3)");
        invite(tenant, user.getId(), "NOW(3) - INTERVAL 31 DAY");
        invite(tenant, user.getId(), "NOW(3) + INTERVAL 1 DAY");
        jdbc.update("INSERT INTO audit_log (action, entity, created_at) VALUES ('OLD', 't', NOW(3) - INTERVAL 400 DAY)");

        CleanupJob.Result r = job.run(Instant.now());

        assertThat(r.sessions()).isGreaterThanOrEqualTo(1);
        assertThat(count("user_session WHERE id = ?", oldSession)).isZero();
        assertThat(count("user_session WHERE id = ?", liveSession)).isOne();
        assertThat(count("refresh_token WHERE session_id = ?", liveSession)).isOne();
        assertThat(count("login_attempt WHERE created_at < NOW(3) - INTERVAL 30 DAY")).isZero();
        assertThat(count("login_attempt WHERE created_at > NOW(3) - INTERVAL 1 DAY")).isPositive();
        assertThat(count("user_invite WHERE user_id = ?", user.getId())).isOne();
        assertThat(count("audit_log WHERE action = 'OLD'")).isOne(); // the audit log is never cleaned
    }

    private String session(long tenant, long userId, String lastSeen) {
        String id = Ulid.next();
        jdbc.update("INSERT INTO user_session (id, tenant_id, user_id, created_at, last_seen_at) VALUES (?, ?, ?, "
                + lastSeen + ", " + lastSeen + ")", id, tenant, userId);
        return id;
    }

    private void token(String sessionId, String expires) {
        jdbc.update("INSERT INTO refresh_token (session_id, family_id, token_hash, expires_at) VALUES (?, ?, ?, " + expires + ")",
                sessionId, Ulid.next(), (Ulid.next() + Ulid.next() + Ulid.next()).substring(0, 64));
    }

    private void attempt(String at) {
        jdbc.update("INSERT INTO login_attempt (ip, email_hash, success, created_at) VALUES ('10.9.9.9', ?, false, " + at + ")",
                "e".repeat(64));
    }

    private void invite(long tenant, long userId, String expires) {
        jdbc.update("INSERT INTO user_invite (tenant_id, user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, "
                + expires + ", NOW(3))", tenant, userId, (Ulid.next() + Ulid.next() + Ulid.next()).substring(0, 64));
    }

    private int count(String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + where, Integer.class, args);
    }

    private long newTenant() {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, created_at) VALUES (?, 'T', 'TEST', NOW(3))", publicId);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE public_id = ?", Long.class, publicId);
    }
}
