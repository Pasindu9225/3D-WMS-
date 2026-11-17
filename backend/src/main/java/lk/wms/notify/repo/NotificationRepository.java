package lk.wms.notify.repo;

import java.time.Instant;
import java.util.Optional;
import lk.wms.notify.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(long userId, Pageable pageable);

    Optional<Notification> findByPublicIdAndUserId(String publicId, long userId);

    long countByUserIdAndReadAtIsNull(long userId);

    boolean existsByUserIdAndDedupe(long userId, String dedupe);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int readAll(@Param("userId") long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
