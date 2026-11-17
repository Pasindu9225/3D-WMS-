package lk.wms.inventory.repo;

import lk.wms.inventory.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert-only use: the ledger is never updated or deleted. */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Pick lines per product since a moment, as [product_id, count] (ABC classes). */
    @org.springframework.data.jpa.repository.Query("select m.productId, count(m) from StockMovement m"
            + " where m.type = lk.wms.inventory.domain.StockMovement.Type.PICK and m.createdAt >= :since group by m.productId")
    java.util.List<Object[]> pickCounts(@org.springframework.data.repository.query.Param("since") java.time.Instant since);

    /** A move already made with this Idempotency-Key (tenant-filtered). */
    boolean existsByClientRef(String clientRef);

    /** The ledger of one warehouse, newest first, optionally for one product, one bin (either side) or one type. */
    @org.springframework.data.jpa.repository.Query("""
            select m from StockMovement m
            where m.warehouseId = :warehouseId and (:productId is null or m.productId = :productId)
              and (:binId is null or m.fromBinId = :binId or m.toBinId = :binId)
              and (:type is null or m.type = :type) and m.createdAt >= :from and m.createdAt < :to
            order by m.id desc""")
    org.springframework.data.domain.Page<StockMovement> search(
            @org.springframework.data.repository.query.Param("warehouseId") long warehouseId,
            @org.springframework.data.repository.query.Param("productId") Long productId,
            @org.springframework.data.repository.query.Param("binId") Long binId,
            @org.springframework.data.repository.query.Param("type") StockMovement.Type type,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to,
            org.springframework.data.domain.Pageable pageable);

    /** Ledger lines of some types in a warehouse since a moment, oldest first (dashboard figures). */
    @org.springframework.data.jpa.repository.Query("select m from StockMovement m where m.warehouseId = :warehouseId"
            + " and m.type in :types and m.createdAt >= :from order by m.createdAt, m.id")
    java.util.List<StockMovement> since(@org.springframework.data.repository.query.Param("warehouseId") long warehouseId,
            @org.springframework.data.repository.query.Param("types") java.util.Collection<StockMovement.Type> types,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from);
}
