package lk.wms.auth.repo;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import lk.wms.auth.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter is added by Hibernate (@TenantId); see TenantContext. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByPublicId(String publicId);

    /** Login: row lock so parallel wrong passwords cannot lose failed-login counts. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.publicId = :publicId")
    Optional<AppUser> lockByPublicId(@Param("publicId") String publicId);

    /** Login lookup: call inside TenantContext.callAsSystem, since the tenant is not known yet. */
    Optional<AppUser> findByEmail(String email);

    // Role tables are global (not tenant data), so native SQL with :params is fine here.
    // ponytail: replace with entity queries if roles become per-tenant.
    @Query(value = "SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id"
            + " WHERE ur.user_id = :userId ORDER BY r.code", nativeQuery = true)
    List<String> findRoleCodes(@Param("userId") long userId);

    @Query(value = "SELECT DISTINCT p.code FROM user_role ur"
            + " JOIN role_permission rp ON rp.role_id = ur.role_id"
            + " JOIN permission p ON p.id = rp.permission_id"
            + " WHERE ur.user_id = :userId", nativeQuery = true)
    List<String> findPermissionCodes(@Param("userId") long userId);
}
