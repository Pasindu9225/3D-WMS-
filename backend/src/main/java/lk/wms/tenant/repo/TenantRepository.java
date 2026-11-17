package lk.wms.tenant.repo;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import lk.wms.tenant.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /** Every active company (system jobs such as the weekly ABC classes). */
    @Query("select t.id from Tenant t where t.status = lk.wms.tenant.domain.Tenant.Status.ACTIVE order by t.id")
    java.util.List<Long> activeIds();

    @Query("select t.adjustApprovalMinor from Tenant t where t.id = :id")
    Optional<Long> findAdjustApprovalMinor(@Param("id") long id);

    @Query("select t.name from Tenant t where t.id = :id")
    Optional<String> findName(@Param("id") long id);

    @Query("select t.status from Tenant t where t.id = :id")
    Optional<Tenant.Status> findStatus(@Param("id") long id);

    /** Row lock: two warehouse creations at once cannot both slip under the plan limit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t.maxWarehouses from Tenant t where t.id = :id")
    Optional<Integer> lockMaxWarehouses(@Param("id") long id);
}
