package lk.wms.tenant.repo;

import java.util.Optional;
import lk.wms.tenant.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    @Query("select t.status from Tenant t where t.id = :id")
    Optional<Tenant.Status> findStatus(@Param("id") long id);
}
