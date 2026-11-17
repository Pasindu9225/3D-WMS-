package lk.wms.inventory.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import lk.wms.inventory.domain.CountPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface CountPlanRepository extends JpaRepository<CountPlan, Long> {

    Optional<CountPlan> findByPublicId(String publicId);

    /** Every change to a plan locks its row, so two counters or a counter and the manager never clash. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CountPlan p where p.publicId = :publicId")
    Optional<CountPlan> lockByPublicId(@Param("publicId") String publicId);

    @Query("""
            select p from CountPlan p
            where (:allWarehouses = true or p.warehouseId in :warehouseIds) and p.status in :statuses""")
    Page<CountPlan> search(@Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("statuses") Collection<CountPlan.Status> statuses, Pageable pageable);
}
