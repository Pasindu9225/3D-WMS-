package lk.wms.outbound.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.outbound.domain.SalesOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    @Query("select distinct o from SalesOrder o left join fetch o.lines where o.publicId = :publicId")
    Optional<SalesOrder> findByPublicId(@Param("publicId") String publicId);

    /** These orders, write-locked in id order (dispatch: a fixed lock order, so two dispatchers never deadlock). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.publicId in :ids order by o.id")
    List<SalesOrder> lockByPublicIds(@Param("ids") Collection<String> ids);

    List<SalesOrder> findByShipmentIdOrderById(Long shipmentId);

    long countByWarehouseIdAndStatus(Long warehouseId, SalesOrder.Status status);

    /** Orders by internal id (pick lists point at them). */
    @Query("select o from SalesOrder o where o.id in :ids")
    List<SalesOrder> byInternalIds(@Param("ids") Collection<Long> ids);

    /** Customer refs already used, for a CSV import that names several customers. */
    @Query("select o.customerId, o.customerRef from SalesOrder o where o.customerId in :customerIds and o.customerRef in :refs")
    List<Object[]> refsTaken(@Param("customerIds") Collection<Long> customerIds, @Param("refs") Collection<String> refs);

    /**
     * Orders in the given warehouses (allWarehouses for a company admin) in the given statuses; the text matches
     * the number, customer code or customer ref from the start, or the customer name anywhere ('!' escapes).
     */
    @Query("""
            select o from SalesOrder o
            where (:allWarehouses = true or o.warehouseId in :warehouseIds)
              and o.status in :statuses
              and (:q = '' or o.number like concat(:q, '%') escape '!' or o.customerCode like concat(:q, '%') escape '!'
                   or o.customerRef like concat(:q, '%') escape '!' or o.customerName like concat('%', :q, '%') escape '!')""")
    Page<SalesOrder> search(@Param("allWarehouses") boolean allWarehouses,
            @Param("warehouseIds") Collection<Long> warehouseIds, @Param("statuses") Collection<SalesOrder.Status> statuses,
            @Param("q") String q, Pageable pageable);
}
