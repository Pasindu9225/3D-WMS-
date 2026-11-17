package lk.wms.tenant.repo;

import java.util.List;
import java.util.Optional;
import lk.wms.tenant.domain.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByCode(String code);

    /** ponytail: a handful of plans; no paging. */
    List<Plan> findAllByOrderByPricePerWarehouseMinorAscCodeAsc();
}
