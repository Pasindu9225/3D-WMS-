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

    /** Every bin of a warehouse with where it stands: for putaway suggestions. */
    interface PlacedBin {
        Long getId();

        String getPublicId();

        String getCode();

        Boolean getBlocked();

        Long getMaxWeightG();

        Long getMaxVolumeCm3();

        Short getLevelNo();

        Integer getX();

        Integer getY();

        lk.wms.layout.domain.Zone.Type getZoneType();
    }

    @Query("""
            select b.id as id, b.publicId as publicId, b.code as code, b.blocked as blocked, b.maxWeightG as maxWeightG,
                   b.maxVolumeCm3 as maxVolumeCm3, l.levelNo as levelNo, r.posXMm as x, r.posYMm as y, z.type as zoneType
            from Bin b, lk.wms.layout.domain.RackLevel l, lk.wms.layout.domain.Rack r, lk.wms.layout.domain.Zone z
            where b.levelId = l.id and l.rackId = r.id and r.zoneId = z.id and b.warehouseId = :warehouseId""")
    List<PlacedBin> placedBins(@Param("warehouseId") long warehouseId);

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
