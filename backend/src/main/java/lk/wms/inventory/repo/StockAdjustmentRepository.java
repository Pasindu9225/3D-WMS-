package lk.wms.inventory.repo;

import java.util.Collection;
import java.util.Optional;
import lk.wms.inventory.domain.StockAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    Optional<StockAdjustment> findByPublicId(String publicId);

    Optional<StockAdjustment> findByClientRef(String clientRef);

    /** Adjustments in the given warehouses (allWarehouses for a company admin) and statuses, newest first. */
    @Query("""
            select a from StockAdjustment a
            where (:allWarehouses = true or a.warehouseId in :warehouseIds) and a.status in :statuses
            order by a.id desc""")
    Page<StockAdjustment> search(@Param("allWarehouses") boolean allWarehouses,
            @Param("warehouseIds") Collection<Long> warehouseIds, @Param("statuses") Collection<StockAdjustment.Status> statuses,
            Pageable pageable);
}
