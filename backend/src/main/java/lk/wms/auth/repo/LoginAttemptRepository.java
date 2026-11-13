package lk.wms.auth.repo;

import java.time.Instant;
import lk.wms.auth.domain.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    @Query("select count(a) from LoginAttempt a where a.ip = :ip and a.success = false and a.createdAt > :since")
    long countFailuresFromIp(@Param("ip") String ip, @Param("since") Instant since);

    @Modifying
    @Query("delete from LoginAttempt a where a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
