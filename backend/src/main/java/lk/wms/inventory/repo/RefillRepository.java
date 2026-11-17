package lk.wms.inventory.repo;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import lk.wms.inventory.domain.RefillRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Refill rules; tenant filter by @TenantId. */
public interface RefillRepository extends JpaRepository<RefillRule, Long> {

    Optional<RefillRule> findByPublicId(String publicId);

    /** The active rule for a bin and product, locked: two picks below min at once make one task, not two. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefillRule r where r.binId = :binId and r.productId = :productId and r.active = true")
    Optional<RefillRule> lockActive(@Param("binId") long binId, @Param("productId") long productId);

    @Query("select r from RefillRule r where r.warehouseId = :warehouseId order by r.id")
    List<RefillRule> inWarehouse(@Param("warehouseId") long warehouseId);

    boolean existsByBinIdAndProductId(Long binId, Long productId);
}
