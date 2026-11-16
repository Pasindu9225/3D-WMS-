package lk.wms.outbound.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import lk.wms.outbound.domain.PickList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface PickListRepository extends JpaRepository<PickList, Long> {

    @Query("select distinct p from PickList p left join fetch p.lines where p.publicId = :publicId")
    Optional<PickList> findByPublicId(@Param("publicId") String publicId);

    /** Each confirm or short locks its list: one change at a time, so a line is never picked twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PickList p where p.publicId = :publicId")
    Optional<PickList> lockByPublicId(@Param("publicId") String publicId);

    Optional<PickList> findByOrderId(Long orderId);

    /** Lists in these warehouses (allWarehouses for a company admin) in these statuses, oldest first. */
    @Query("""
            select p from PickList p
            where (:allWarehouses = true or p.warehouseId in :warehouseIds) and p.status in :statuses""")
    Page<PickList> search(@Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("statuses") Collection<PickList.Status> statuses, Pageable pageable);
}
