package lk.wms.layout.repo;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import lk.wms.layout.domain.Warehouse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId; archived rows hidden by @SQLRestriction. */
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByPublicId(String publicId);

    /** Serialises layout saves and publishes of one warehouse. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w where w.publicId = :publicId")
    Optional<Warehouse> lockByPublicId(@Param("publicId") String publicId);

    /** Warehouses a non-admin user is assigned to. */
    @Query("select w from Warehouse w where exists"
            + " (select 1 from UserWarehouse uw where uw.warehouseId = w.id and uw.userId = :userId)")
    Page<Warehouse> findAssigned(@Param("userId") long userId, Pageable pageable);

    @Query("select count(uw) > 0 from UserWarehouse uw where uw.warehouseId = :warehouseId and uw.userId = :userId")
    boolean isAssigned(@Param("warehouseId") long warehouseId, @Param("userId") long userId);

    /** Active (not archived) warehouses: what the plan limit counts. */
    @Query("select count(w) from Warehouse w")
    long countActive();
}
