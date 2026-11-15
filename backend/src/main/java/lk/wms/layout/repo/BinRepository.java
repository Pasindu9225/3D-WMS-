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

    Optional<Bin> findByWarehouseIdAndCode(long warehouseId, String code);

    /** Stock moving into a bin locks it first, so two workers cannot both fill its last space. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bin b where b.id = :id")
    Optional<Bin> lockForStock(@Param("id") long id);

    /** Bin list: optional code prefix (e.g. "A-03" for one rack). Spring escapes % and _ in StartingWith. */
    Page<Bin> findByWarehouseIdAndCodeStartingWith(long warehouseId, String codePrefix, Pageable pageable);

    long countByWarehouseId(long warehouseId);

    /** 3D scene: just what the viewer needs per bin, in id order so status flags line up with it. */
    interface SceneBin {
        String getPublicId();

        String getCode();

        Long getLevelId();

        Integer getPositionNo();

        Boolean getPickFace();

        Boolean getBlocked();
    }

    List<SceneBin> findByWarehouseIdOrderById(long warehouseId);

    interface CodeOnly {
        String getCode();
    }

    /** Label printing: codes only, in order. Spring escapes % and _ in StartingWith. */
    List<CodeOnly> findByWarehouseIdAndCodeStartingWithOrderByCode(long warehouseId, String codePrefix);

    @Modifying
    @Query("delete from Bin b where b.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
