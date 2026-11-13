package lk.wms.auth.repo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import lk.wms.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Row lock: two parallel refreshes with the same token cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t join fetch t.session where t.tokenHash = :hash")
    Optional<RefreshToken> lockByHash(@Param("hash") String hash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.session.id = :sessionId and t.revokedAt is null")
    int revokeBySession(@Param("sessionId") String sessionId, @Param("now") Instant now);
}
