package lk.wms.catalog.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.catalog.domain.Partner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId; archived partners hidden by @SQLRestriction. */
public interface PartnerRepository extends JpaRepository<Partner, Long> {

    Optional<Partner> findByPublicIdAndType(String publicId, Partner.Type type);

    /** Code starting with the text or name containing it; the text is escaped by the service ('!' escapes). */
    @Query("""
            select p from Partner p
            where p.type = :type and (:q = '' or p.code like concat(:q, '%') escape '!'
               or p.name like concat('%', :q, '%') escape '!')""")
    Page<Partner> search(@Param("type") Partner.Type type, @Param("q") String q, Pageable pageable);

    List<Partner> findByTypeAndCodeIn(Partner.Type type, Collection<String> codes);
}
