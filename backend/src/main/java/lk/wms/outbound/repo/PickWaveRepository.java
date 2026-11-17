package lk.wms.outbound.repo;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.outbound.domain.PickWave;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface PickWaveRepository extends JpaRepository<PickWave, Long> {

    Optional<PickWave> findByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PickWave w where w.publicId = :publicId")
    Optional<PickWave> lockByPublicId(@Param("publicId") String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PickWave w where w.id = :id")
    Optional<PickWave> lockById(@Param("id") long id);

    @Query("select w from PickWave w where w.id in :ids")
    List<PickWave> byInternalIds(@Param("ids") Collection<Long> ids);

    @Query("""
            select w from PickWave w
            where (:allWarehouses = true or w.warehouseId in :warehouseIds) and w.status in :statuses order by w.id""")
    Page<PickWave> search(@Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("statuses") Collection<PickWave.Status> statuses, Pageable pageable);
}
