package lk.wms.layout.repo;

import java.util.Collection;
import java.util.List;
import lk.wms.layout.domain.Rack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Published racks. Written only by LayoutPublisher. */
public interface RackRepository extends JpaRepository<Rack, Long> {

    List<Rack> findByWarehouseId(long warehouseId);

    @Modifying
    @Query("delete from Rack r where r.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
