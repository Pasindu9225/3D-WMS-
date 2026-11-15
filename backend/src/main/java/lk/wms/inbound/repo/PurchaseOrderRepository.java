package lk.wms.inbound.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import lk.wms.inbound.domain.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("select distinct o from PurchaseOrder o left join fetch o.lines where o.publicId = :publicId")
    Optional<PurchaseOrder> findByPublicId(@Param("publicId") String publicId);

    /** Opening a receipt locks its order, so two phones cannot open two receipts for one delivery. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.publicId = :publicId")
    Optional<PurchaseOrder> lockByPublicId(@Param("publicId") String publicId);

    /**
     * Orders in the given warehouses (allWarehouses for a company admin) in the given statuses; the text matches
     * the number or supplier code from the start, or the supplier name anywhere ('!' escapes, set by the service).
     */
    @Query("""
            select o from PurchaseOrder o
            where (:allWarehouses = true or o.warehouseId in :warehouseIds)
              and o.status in :statuses
              and (:q = '' or o.number like concat(:q, '%') escape '!' or o.supplierCode like concat(:q, '%') escape '!'
                   or o.supplierName like concat('%', :q, '%') escape '!')""")
    Page<PurchaseOrder> search(@Param("allWarehouses") boolean allWarehouses,
            @Param("warehouseIds") Collection<Long> warehouseIds, @Param("statuses") Collection<PurchaseOrder.Status> statuses,
            @Param("q") String q, Pageable pageable);
}
