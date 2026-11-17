package lk.wms.outbound.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.outbound.domain.ReturnOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {

    Optional<ReturnOrder> findByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnOrder r where r.publicId = :publicId")
    Optional<ReturnOrder> lockByPublicId(@Param("publicId") String publicId);

    /** Earlier returns of one order that are not cancelled (what was already taken back). */
    @Query("select r from ReturnOrder r where r.orderId = :orderId and r.status <> lk.wms.outbound.domain.ReturnOrder.Status.CANCELLED")
    List<ReturnOrder> liveForOrder(@Param("orderId") long orderId);

    @Query("""
            select r from ReturnOrder r
            where (:allWarehouses = true or r.warehouseId in :warehouseIds) and r.status in :statuses""")
    Page<ReturnOrder> search(@Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("statuses") Collection<ReturnOrder.Status> statuses, Pageable pageable);
}
