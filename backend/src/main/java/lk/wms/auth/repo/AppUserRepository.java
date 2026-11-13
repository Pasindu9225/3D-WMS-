package lk.wms.auth.repo;

import java.util.Optional;
import lk.wms.auth.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant filter is added by Hibernate (@TenantId); see TenantContext. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByPublicId(String publicId);

    /** Login lookup: call inside TenantContext.callAsSystem, since the tenant is not known yet. */
    Optional<AppUser> findByEmail(String email);
}
