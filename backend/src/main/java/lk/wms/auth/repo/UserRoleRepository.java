package lk.wms.auth.repo;

import java.util.Collection;
import java.util.List;
import lk.wms.auth.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter added by Hibernate (@TenantId). */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.Key> {

    /** One query for a whole page of users (no N+1). */
    interface UserRoleCode {
        Long getUserId();

        String getCode();
    }

    @Query("select ur.userId as userId, r.code as code from UserRole ur, Role r"
            + " where r.id = ur.roleId and ur.userId in :userIds order by r.code")
    List<UserRoleCode> findCodesForUsers(@Param("userIds") Collection<Long> userIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.userId = :userId")
    int deleteAllForUser(@Param("userId") long userId);
}
