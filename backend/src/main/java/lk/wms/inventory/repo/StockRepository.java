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
    @Query(value = "INSERT INTO stock (tenant_id, warehouse_id, bin_id, area, product_id, batch_no, expiry_date, qty_on_hand,"
            + " qty_reserved, received_at) VALUES (:tenantId, :warehouseId, :binId, :area, :productId, :batchNo, :expiry, 0, 0, :now)"
            + " ON DUPLICATE KEY UPDATE qty_on_hand = qty_on_hand", nativeQuery = true)
    int ensure(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId, @Param("binId") Long binId,
            @Param("area") String area, @Param("productId") long productId, @Param("batchNo") String batchNo, @Param("expiry") LocalDate expiry,
            @Param("now") Instant now);

    /** The row for one place, product, batch and expiry, write-locked (the unique key's stand-in columns find it). */
    @Query(value = "SELECT * FROM stock WHERE tenant_id = :tenantId AND warehouse_id = :warehouseId"
            + " AND bin_key = IFNULL(:binId, 0) AND area = :area AND product_id = :productId AND batch_key = IFNULL(:batchNo, '')"
            + " AND expiry_key = IFNULL(:expiry, DATE '1000-01-01') FOR UPDATE", nativeQuery = true)
    Optional<Stock> lockPlace(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
            @Param("binId") Long binId, @Param("area") String area, @Param("productId") long productId, @Param("batchNo") String batchNo,
            @Param("expiry") LocalDate expiry);

    /**
     * Ids of stock rows in bins that still have something free for these products, in bin order: the order the
     * caller then locks them in (ids only, so no stale entity copies are left in the session).
     */
    @Query("select s.id from Stock s where s.warehouseId = :warehouseId and s.productId in :productIds and s.binId is not null"
            + " and s.qtyOnHand > s.qtyReserved order by s.binId, s.id")
    List<Long> reservableIds(@Param("warehouseId") long warehouseId, @Param("productIds") Collection<Long> productIds);

    /** One stock row, write-locked. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.id = :id")
    Optional<Stock> lockById(@Param("id") long id);

    /** What a bin holds now as [product_id, qty_on_hand], read with locks (capacity check, bin row already locked). */
    @Query(value = "SELECT product_id, qty_on_hand FROM stock WHERE bin_id = :binId FOR SHARE", nativeQuery = true)
    List<Object[]> contentsForUpdate(@Param("binId") long binId);

    /** The lots of a product in one bin (or, with binId null, in the receiving area), oldest first. */
    @Query("""
            select s from Stock s where s.warehouseId = :warehouseId and s.productId = :productId and s.qtyOnHand > 0
              and ((:binId is null and s.area = 'RECEIVING') or s.binId = :binId)
            order by s.receivedAt, s.id""")
    List<Stock> lots(@Param("warehouseId") long warehouseId, @Param("binId") Long binId, @Param("productId") long productId);

    /** Free stock of a product in the bins of a warehouse (v1.2 refill: where to bring a pick face's stock from). */
    @Query("select s from Stock s where s.warehouseId = :warehouseId and s.productId = :productId and s.binId is not null"
            + " and s.qtyOnHand > s.qtyReserved")
    List<Stock> freeInBins(@Param("warehouseId") long warehouseId, @Param("productId") long productId);

    /** Stock rows of a warehouse, optionally one product, a set of bins, or only the receiving area. */
    @Query("""
            select s from Stock s
            where s.warehouseId = :warehouseId and s.qtyOnHand > 0 and (:productId is null or s.productId = :productId)
              and (:anyBin = true or s.binId in :binIds) and (:receivingOnly = false or s.area = 'RECEIVING')
            order by s.productId, s.binId, s.receivedAt""")
    org.springframework.data.domain.Page<Stock> search(@Param("warehouseId") long warehouseId, @Param("productId") Long productId,
            @Param("anyBin") boolean anyBin, @Param("binIds") Collection<Long> binIds, @Param("receivingOnly") boolean receivingOnly,
            org.springframework.data.domain.Pageable pageable);

    /** Totals per product: [product_id, on hand, in receiving, bins, reserved, in dispatch]. */
    @Query(value = """
            select s.productId, sum(s.qtyOnHand), sum(case when s.area = 'RECEIVING' then s.qtyOnHand else 0 end), count(distinct s.binId),
                   sum(s.qtyReserved), sum(case when s.area = 'DISPATCH' then s.qtyOnHand else 0 end)
            from Stock s where s.warehouseId = :warehouseId and s.qtyOnHand > 0 and (:anyProduct = true or s.productId in :productIds)
            group by s.productId order by s.productId""",
            countQuery = """
            select count(distinct s.productId) from Stock s
            where s.warehouseId = :warehouseId and s.qtyOnHand > 0 and (:anyProduct = true or s.productId in :productIds)""")
    org.springframework.data.domain.Page<Object[]> totalsByProduct(@Param("warehouseId") long warehouseId,
            @Param("anyProduct") boolean anyProduct, @Param("productIds") Collection<Long> productIds,
            org.springframework.data.domain.Pageable pageable);

    /** What every bin of a warehouse holds, as [bin_id, product_id, qty_on_hand] (3D fill levels). */
    @Query("select s.binId, s.productId, s.qtyOnHand from Stock s where s.warehouseId = :warehouseId and s.binId is not null and s.qtyOnHand > 0")
    List<Object[]> binContents(@Param("warehouseId") long warehouseId);

    /** Stock in the bins of a warehouse (optionally only some bins / products), for a count plan. */
    @Query("select s from Stock s where s.warehouseId = :warehouseId and s.binId is not null and s.qtyOnHand > 0"
            + " and (:allBins = true or s.binId in :binIds) and (:allProducts = true or s.productId in :productIds)")
    List<Stock> inBins(@Param("warehouseId") long warehouseId, @Param("allBins") boolean allBins,
            @Param("binIds") java.util.Collection<Long> binIds, @Param("allProducts") boolean allProducts,
            @Param("productIds") java.util.Collection<Long> productIds, org.springframework.data.domain.Pageable limit);

    /** The earliest expiry date in each bin that holds dated stock, as [bin_id, min expiry] (3D expiry colours). */
    @Query("select s.binId, min(s.expiryDate) from Stock s where s.warehouseId = :warehouseId and s.binId is not null"
            + " and s.qtyOnHand > 0 and s.expiryDate is not null group by s.binId")
    List<Object[]> binEarliestExpiry(@Param("warehouseId") long warehouseId);

    /** Stock with an expiry date before :before (expired ones too), earliest first: the dashboard's expiry list. */
    @Query("select s from Stock s where s.warehouseId = :warehouseId and s.qtyOnHand > 0 and s.expiryDate is not null"
            + " and s.expiryDate < :before order by s.expiryDate, s.id")
    List<Stock> expiringBefore(@Param("warehouseId") long warehouseId, @Param("before") java.time.LocalDate before,
            org.springframework.data.domain.Pageable pageable);

    /** Goods received but not yet put away in one warehouse, oldest first. */
    @Query("select s from Stock s where s.warehouseId = :warehouseId and s.area = 'RECEIVING' and s.qtyOnHand > 0 order by s.receivedAt, s.id")
    org.springframework.data.domain.Page<Stock> awaitingPutaway(@Param("warehouseId") long warehouseId,
            org.springframework.data.domain.Pageable pageable);

    /** Everything in the warehouse's bins (putaway suggestions: what is where, and how full). */
    @Query("select s from Stock s where s.warehouseId = :warehouseId and s.binId is not null and s.qtyOnHand > 0")
    List<Stock> inBins(@Param("warehouseId") long warehouseId);

    /** Bins among these that hold anything (a publish may not remove them). */
    @Query("select distinct s.binId from Stock s where s.binId in :binIds and s.qtyOnHand > 0")
    List<Long> binsWithStock(@Param("binIds") Collection<Long> binIds);

    /** Total on hand of a product in a warehouse, all places (tests and reports). */
    @Query("select coalesce(sum(s.qtyOnHand), 0) from Stock s where s.warehouseId = :warehouseId and s.productId = :productId")
    BigDecimal totalOf(@Param("warehouseId") long warehouseId, @Param("productId") long productId);
}
