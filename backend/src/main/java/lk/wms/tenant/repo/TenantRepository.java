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

    /** Customer companies (never the platform one) for the Super Admin list; name search uses ESCAPE '!'. */
    @Query("select t from Tenant t where t.platform = false and (:q = '' or lower(t.name) like lower(concat('%', :q, '%')) escape '!')"
            + " and (:status is null or t.status = :status)")
    org.springframework.data.domain.Page<Tenant> search(@Param("q") String q, @Param("status") Tenant.Status status,
            org.springframework.data.domain.Pageable pageable);

    Optional<Tenant> findByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.publicId = :publicId")
    Optional<Tenant> lockByPublicId(@Param("publicId") String publicId);

    Optional<Tenant> findFirstByPlatformTrue();

    long countByPlanCode(String planCode);

    /** Row lock: two warehouse creations at once cannot both slip under the plan limit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t.maxWarehouses from Tenant t where t.id = :id")
    Optional<Integer> lockMaxWarehouses(@Param("id") long id);
}
