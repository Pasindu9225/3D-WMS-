package lk.wms.layout.repo;

import java.util.Collection;
import java.util.List;
import lk.wms.layout.domain.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Published zones. Written only by LayoutPublisher. */
public interface ZoneRepository extends JpaRepository<Zone, Long> {

    List<Zone> findByWarehouseId(long warehouseId);

    @Modifying
    @Query("delete from Zone z where z.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
