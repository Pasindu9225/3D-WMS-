package lk.wms.common.audit;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** audit_log is not TenantScoped (system rows have no tenant), so every read names the tenant itself. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("select a from AuditLog a where a.tenantId = :tenantId"
            + " and (:entity is null or a.entity = :entity)"
            + " and (:userId is null or a.userId = :userId)"
            + " and (:from is null or a.createdAt >= :from)"
            + " and (:to is null or a.createdAt < :to)")
    Page<AuditLog> search(@Param("tenantId") long tenantId, @Param("entity") String entity,
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
