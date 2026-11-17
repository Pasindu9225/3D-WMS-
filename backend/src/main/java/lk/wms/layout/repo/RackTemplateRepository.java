package lk.wms.layout.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.layout.domain.RackTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant filter by @TenantId; archived templates hidden by @SQLRestriction. */
public interface RackTemplateRepository extends JpaRepository<RackTemplate, Long> {

    Optional<RackTemplate> findByPublicId(String publicId);

    List<RackTemplate> findByPublicIdIn(Collection<String> publicIds);

    Page<RackTemplate> findAllBy(Pageable pageable);

    /** Templates of published racks (3D look); archived ones are absent and get the default look. */
    List<RackTemplate> findByIdIn(Collection<Long> ids);
}
