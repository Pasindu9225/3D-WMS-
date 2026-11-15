package lk.wms.inventory.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lk.wms.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId (the native upsert gets the tenant passed in by the service). */
public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * Creates the row for this place and product (0 on hand), or does nothing when it exists; either way the
     * row is write-locked until commit. Gives every movement a row to lock, in a fixed order, before it changes.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = "INSERT INTO stock (tenant_id, warehouse_id, bin_id, product_id, batch_no, expiry_date, qty_on_hand,"
            + " qty_reserved, received_at) VALUES (:tenantId, :warehouseId, :binId, :productId, :batchNo, :expiry, 0, 0, :now)"
            + " ON DUPLICATE KEY UPDATE qty_on_hand = qty_on_hand", nativeQuery = true)
    int ensure(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId, @Param("binId") Long binId,
            @Param("productId") long productId, @Param("batchNo") String batchNo, @Param("expiry") LocalDate expiry,
            @Param("now") Instant now);

    /** The row for one place, product, batch and expiry, write-locked (the unique key's stand-in columns find it). */
    @Query(value = "SELECT * FROM stock WHERE tenant_id = :tenantId AND warehouse_id = :warehouseId"
            + " AND bin_key = IFNULL(:binId, 0) AND product_id = :productId AND batch_key = IFNULL(:batchNo, '')"
            + " AND expiry_key = IFNULL(:expiry, DATE '1000-01-01') FOR UPDATE", nativeQuery = true)
    Optional<Stock> lockPlace(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
            @Param("binId") Long binId, @Param("productId") long productId, @Param("batchNo") String batchNo,
            @Param("expiry") LocalDate expiry);

    /** What a bin holds now as [product_id, qty_on_hand], read with locks (capacity check, bin row already locked). */
    @Query(value = "SELECT product_id, qty_on_hand FROM stock WHERE bin_id = :binId FOR SHARE", nativeQuery = true)
    List<Object[]> contentsForUpdate(@Param("binId") long binId);

    /** Bins among these that hold anything (a publish may not remove them). */
    @Query("select distinct s.binId from Stock s where s.binId in :binIds and s.qtyOnHand > 0")
    List<Long> binsWithStock(@Param("binIds") Collection<Long> binIds);

    /** Total on hand of a product in a warehouse, all places (tests and reports). */
    @Query("select coalesce(sum(s.qtyOnHand), 0) from Stock s where s.warehouseId = :warehouseId and s.productId = :productId")
    BigDecimal totalOf(@Param("warehouseId") long warehouseId, @Param("productId") long productId);
}
