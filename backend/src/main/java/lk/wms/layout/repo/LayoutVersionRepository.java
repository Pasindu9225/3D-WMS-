package lk.wms.layout.repo;

import java.util.Optional;
import lk.wms.layout.domain.LayoutVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LayoutVersionRepository extends JpaRepository<LayoutVersion, Long> {

    Optional<LayoutVersion> findFirstByWarehouseIdAndStatus(long warehouseId, LayoutVersion.Status status);

    Optional<LayoutVersion> findFirstByWarehouseIdAndStatusOrderByPublishedAtDesc(long warehouseId,
            LayoutVersion.Status status);
}
