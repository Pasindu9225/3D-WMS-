package lk.wms.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lk.wms.IntegrationTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.common.Ulid;
import org.hibernate.PropertyValueException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/** SEC-24 / SEC-10: one tenant can never read or write another tenant's rows. */
class TenantIsolationTest extends IntegrationTest {

    @Autowired
    AppUserRepository users;
    @Autowired
    JdbcTemplate jdbc;

    long tenantA;
    long tenantB;
    AppUser userA;
    AppUser userB;

    @BeforeEach
    void twoTenantsWithOneUserEach() {
        tenantA = newTenant("A");
        tenantB = newTenant("B");
        userA = TenantContext.callAs(tenantA, () -> users.save(newUser("a")));
        userB = TenantContext.callAs(tenantB, () -> users.save(newUser("b")));
    }

    @Test
    void insertIsStampedWithCurrentTenant() {
        assertThat(userA.getTenantId()).isEqualTo(tenantA);
        assertThat(userB.getTenantId()).isEqualTo(tenantB);
    }

    @Test
    void listsOnlyOwnTenant() {
        List<AppUser> seenByA = TenantContext.callAs(tenantA, users::findAll);
        assertThat(seenByA).extracting(AppUser::getTenantId).containsOnly(tenantA);
        assertThat(seenByA).extracting(AppUser::getPublicId)
                .contains(userA.getPublicId()).doesNotContain(userB.getPublicId());
    }

    @Test
    void cannotReadOtherTenantByPublicIdOrEmail() {
        assertThat(TenantContext.callAs(tenantA, () -> users.findByPublicId(userB.getPublicId()))).isEmpty();
        assertThat(TenantContext.callAs(tenantA, () -> users.findByEmail(userB.getEmail()))).isEmpty();
    }

    @Test
    void cannotWriteIntoOtherTenant() {
        AppUser forged = newUser("forged");
        ReflectionTestUtils.setField(forged, "tenantId", tenantB);
        assertThatThrownBy(() -> TenantContext.callAs(tenantA, () -> users.save(forged)))
                .hasRootCauseInstanceOf(PropertyValueException.class)
                .rootCause().hasMessageContaining("tenant id differs");
        assertThat(TenantContext.callAs(tenantB, () -> users.findByEmail(forged.getEmail()))).isEmpty();
    }

    @Test
    void cannotUpdateOtherTenantRow() {
        userB.rename("hijacked");
        // tenant A cannot even see the row, so the merge is rejected as stale
        assertThatThrownBy(() -> TenantContext.callAs(tenantA, () -> users.save(userB)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(TenantContext.callAs(tenantB, () -> users.findByPublicId(userB.getPublicId())))
                .get().extracting(AppUser::getFullName).isEqualTo("User b");
    }

    @Test
    void noTenantSetFailsClosed() {
        assertThat(users.findAll()).isEmpty();
        assertThat(users.findByPublicId(userA.getPublicId())).isEmpty();
        assertThatThrownBy(() -> users.save(newUser("orphan")))   // tenant_id -1 rejected by UNSIGNED column
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void systemContextSeesAllTenantsForLogin() {
        assertThat(TenantContext.callAsSystem(() -> users.findByEmail(userB.getEmail())))
                .get().extracting(AppUser::getTenantId).isEqualTo(tenantB);
    }

    @Test
    void invalidTenantIdRejected() {
        assertThatThrownBy(() -> TenantContext.callAs(0, () -> null)).isInstanceOf(IllegalArgumentException.class);
    }

    private long newTenant(String name) {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, created_at) VALUES (?, ?, 'TEST', NOW(3))",
                publicId, "Tenant " + name);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE public_id = ?", Long.class, publicId);
    }

    private static AppUser newUser(String name) {
        return new AppUser(name + "-" + Ulid.next() + "@test.lk", "User " + name, "{noop}x", AppUser.Status.ACTIVE);
    }
}
