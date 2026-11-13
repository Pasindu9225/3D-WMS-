package lk.wms.auth.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lk.wms.auth.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    /** Per-request session check (SEC-05). Called in system context: the JWT is verified, the tenant not yet set. */
    @Query("select s from UserSession s join fetch s.user where s.id = :sid")
    Optional<UserSession> findWithUser(@Param("sid") String sid);

    @Query("select s from UserSession s where s.user.id = :userId and s.revokedAt is null")
    List<UserSession> findActiveByUserId(@Param("userId") long userId);

    /** Cleanup: revoked, or unused long enough that its refresh token has expired. */
    @Modifying
    @Query("delete from UserSession s where s.revokedAt < :cutoff or s.lastSeenAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
