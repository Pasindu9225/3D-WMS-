package lk.wms.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lk.wms.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** SEC-27 (least-privilege app user) and SEC-28 (audit log is append-only), run as wms_app. */
class DatabasePrivilegesTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appUserIsWmsApp() {
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).startsWith("wms_app@");
    }

    @Test
    void appUserCannotChangeSchema() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE hack (id INT)"));
        assertThatThrownBy(() -> jdbc.execute("DROP TABLE login_attempt"));
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE app_user ADD COLUMN x INT"));
    }

    @Test
    void appUserCannotSeeOtherDatabases() {
        assertThat(jdbc.queryForList("SHOW DATABASES", String.class))
                .containsOnly("information_schema", "performance_schema", "wms");
    }

    @Test
    void auditLogIsAppendOnly() {
        jdbc.update("INSERT INTO audit_log (action, entity, created_at) VALUES ('TEST', 'test', NOW(3))");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'CHANGED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .hasMessageContaining("append-only");
    }
}
