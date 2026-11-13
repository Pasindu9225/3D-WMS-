package lk.wms.layout.repo;

import java.util.Collection;
import java.util.List;
import lk.wms.layout.domain.RackLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Published rack levels. Written only by LayoutPublisher. */
public interface RackLevelRepository extends JpaRepository<RackLevel, Long> {

    List<RackLevel> findByRackIdIn(Collection<Long> rackIds);

    @Modifying
    @Query("delete from RackLevel l where l.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
