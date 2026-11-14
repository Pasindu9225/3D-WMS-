package lk.wms.catalog.repo;

import java.util.Optional;
import lk.wms.catalog.domain.FileObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId; deleted files hidden by @SQLRestriction. */
public interface FileObjectRepository extends JpaRepository<FileObject, Long> {

    /** By internal id from a product row; a query (not findById) so the tenant filter applies. */
    @Query("select f from FileObject f where f.id = :id")
    Optional<FileObject> findLive(@Param("id") long id);
}
