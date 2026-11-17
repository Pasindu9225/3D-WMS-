package lk.wms.inventory.repo;

import java.util.List;
import java.util.Optional;
import lk.wms.inventory.domain.PutawayRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PutawayRuleRepository extends JpaRepository<PutawayRule, Long> {

    Optional<PutawayRule> findByPublicId(String publicId);

    /** All rules of a warehouse, in the order they are tried within the same kind (ponytail: few per warehouse, no paging). */
    List<PutawayRule> findByWarehouseIdOrderByPriorityAscIdAsc(long warehouseId);
}
