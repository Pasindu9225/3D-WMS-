package lk.wms.auth.repo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import lk.wms.auth.domain.UserInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserInviteRepository extends JpaRepository<UserInvite, Long> {

    /** Accepting an invite: call in system context (the visitor has no session, so no tenant). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from UserInvite i join fetch i.user where i.tokenHash = :hash")
    Optional<UserInvite> lockByHash(@Param("hash") String hash);

    @Modifying
    @Query("update UserInvite i set i.revokedAt = :now where i.user.id = :userId and i.usedAt is null and i.revokedAt is null")
    int revokeOpenForUser(@Param("userId") long userId, @Param("now") Instant now);

    @Query("select count(i) > 0 from UserInvite i where i.user.id = :userId and i.usedAt is not null")
    boolean hasAccepted(@Param("userId") long userId);

    @Query("select count(i) > 0 from UserInvite i where i.user.id = :userId")
    boolean hasAny(@Param("userId") long userId);
}
