package lk.wms.outbound.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.outbound.domain.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByPublicId(String publicId);

    @Query("select s from Shipment s where s.id in :ids")
    List<Shipment> byInternalIds(@Param("ids") Collection<Long> ids);

    @Query("select s from Shipment s where :allWarehouses = true or s.warehouseId in :warehouseIds")
    Page<Shipment> search(@Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") Collection<Long> warehouseIds,
            Pageable pageable);
}
