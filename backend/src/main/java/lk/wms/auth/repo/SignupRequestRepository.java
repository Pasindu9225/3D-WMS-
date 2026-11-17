package lk.wms.auth.repo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import lk.wms.auth.domain.SignupRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SignupRequestRepository extends JpaRepository<SignupRequest, Long> {

    /** Row lock: the same link opened twice at once makes one company. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SignupRequest s where s.tokenHash = :hash")
    Optional<SignupRequest> lockByHash(@Param("hash") String hash);

    /** A new sign-up with the same email replaces any earlier pending one. */
    @Modifying
    @Query("delete from SignupRequest s where s.email = :email and s.usedAt is null")
    int deletePending(@Param("email") String email);

    @Modifying
    @Query("delete from SignupRequest s where s.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
