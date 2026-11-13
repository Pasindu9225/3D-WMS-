package lk.wms.auth.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.auth.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

    @Query("select distinct r from Role r left join fetch r.permissions order by r.id")
    List<Role> findAllWithPermissions();

    List<Role> findByCodeIn(Collection<String> codes);

    Optional<Role> findByCode(String code);
}
