package lk.wms.auth.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Active users of the current company (tenant filter). */
    long countByStatus(AppUser.Status status);

    /** Spring escapes % and _ in "Containing", so the search text is matched literally. */
    Page<AppUser> findByFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email,
            Pageable pageable);

    List<AppUser> findByIdIn(Collection<Long> ids);

    /**
     * Locks every active holder of a role, so two admins cannot remove each other at the same moment
     * and leave the company with none.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u, UserRole ur where ur.userId = u.id and ur.roleId = :roleId"
            + " and u.status = lk.wms.auth.domain.AppUser.Status.ACTIVE")
    List<AppUser> lockActiveWithRole(@Param("roleId") long roleId);

    // Role tables are global (not tenant data), so native SQL with :params is fine here.
    // ponytail: replace with entity queries if roles become per-tenant.
    @Query(value = "SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id"
            + " WHERE ur.user_id = :userId ORDER BY r.code", nativeQuery = true)
    List<String> findRoleCodes(@Param("userId") long userId);

    /** [id, public_id, full_name, is_admin] of active users of a company holding a permission. */
    @Query(value = "SELECT u.id, u.public_id, u.full_name, MAX(r.code = 'TENANT_ADMIN') FROM app_user u"
            + " JOIN user_role ur ON ur.user_id = u.id JOIN role r ON r.id = ur.role_id"
            + " WHERE u.tenant_id = :tenantId AND u.status = 'ACTIVE' AND u.id IN (SELECT ur2.user_id FROM user_role ur2"
            + " JOIN role_permission rp ON rp.role_id = ur2.role_id JOIN permission p ON p.id = rp.permission_id WHERE p.code = :permission)"
            + " GROUP BY u.id, u.public_id, u.full_name ORDER BY u.full_name", nativeQuery = true)
    List<Object[]> holdersOf(@Param("tenantId") long tenantId, @Param("permission") String permission);

    @Query(value = "SELECT DISTINCT p.code FROM user_role ur"
            + " JOIN role_permission rp ON rp.role_id = ur.role_id"
            + " JOIN permission p ON p.id = rp.permission_id"
            + " WHERE ur.user_id = :userId", nativeQuery = true)
    List<String> findPermissionCodes(@Param("userId") long userId);
}
