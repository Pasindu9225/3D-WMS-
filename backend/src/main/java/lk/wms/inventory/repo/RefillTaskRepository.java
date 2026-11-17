package lk.wms.inventory.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.inventory.domain.RefillTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Refill tasks; tenant filter by @TenantId. */
public interface RefillTaskRepository extends JpaRepository<RefillTask, Long> {

    Optional<RefillTask> findByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefillTask t where t.publicId = :publicId")
    Optional<RefillTask> lockByPublicId(@Param("publicId") String publicId);

    boolean existsByRuleIdAndStatus(Long ruleId, RefillTask.Status status);

    @Query("select t from RefillTask t where t.ruleId in :ruleIds and t.status = lk.wms.inventory.domain.RefillTask.Status.OPEN")
    List<RefillTask> openFor(@Param("ruleIds") Collection<Long> ruleIds);

    @Query("""
            select t from RefillTask t
            where (:allWarehouses = true or t.warehouseId in :warehouseIds) and t.status = lk.wms.inventory.domain.RefillTask.Status.OPEN
            order by t.id""")
    Page<RefillTask> open(@Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") Collection<Long> warehouseIds,
            Pageable pageable);

    @Query("select count(t) from RefillTask t where t.warehouseId = :warehouseId and t.status = lk.wms.inventory.domain.RefillTask.Status.OPEN")
    long countOpen(@Param("warehouseId") long warehouseId);
}
