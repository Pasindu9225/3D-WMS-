package lk.wms.layout.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.layout.domain.Bin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Published bins. Layout comes from LayoutPublisher; single-bin edits from BinService. */
public interface BinRepository extends JpaRepository<Bin, Long> {

    List<Bin> findByWarehouseId(long warehouseId);

    Optional<Bin> findByPublicId(String publicId);

    /** Bin list: optional code prefix (e.g. "A-03" for one rack). Spring escapes % and _ in StartingWith. */
    Page<Bin> findByWarehouseIdAndCodeStartingWith(long warehouseId, String codePrefix, Pageable pageable);

    long countByWarehouseId(long warehouseId);

    @Modifying
    @Query("delete from Bin b where b.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
