package lk.wms.catalog.repo;

import java.util.Optional;
import lk.wms.catalog.domain.Product;
import lk.wms.catalog.domain.ProductUom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId; archived products hidden by @SQLRestriction. */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** [id, reorder_point] of live products that have one. */
    @org.springframework.data.jpa.repository.Query("select p.id, p.reorderPoint from Product p where p.reorderPoint is not null")
    java.util.List<Object[]> reorderPoints();

    Optional<Product> findByPublicId(String publicId);

    /** Ids of live products in one ABC class (count plans). */
    @Query("select p.id from Product p where p.abcClass = :abc")
    java.util.List<Long> idsWithAbc(@Param("abc") String abc);

    /** Ids of every live product of the company (the ABC job classifies them all). */
    @Query("select p.id from Product p")
    java.util.List<Long> liveIds();

    /** Sets the ABC class of these products without touching their version (a running edit is not made stale). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("update Product p set p.abcClass = :abc, p.abcAt = :at where p.id in :ids")
    int setAbc(@Param("abc") String abc, @Param("at") java.time.Instant at, @Param("ids") java.util.Collection<Long> ids);

    /**
     * Search: SKU or barcode starting with the text, or name containing it (case-insensitive collation).
     * The text is escaped by the service ('!' escapes % and _), so it is a literal, never a pattern.
     */
    @Query("""
            select p from Product p
            where :q = '' or p.sku like concat(:q, '%') escape '!' or p.barcode like concat(:q, '%') escape '!'
               or p.name like concat('%', :q, '%') escape '!'""")
    Page<Product> search(@Param("q") String q, Pageable pageable);

    Optional<Product> findByBarcode(String barcode);

    Optional<Product> findBySku(String sku);

    /** Products by internal id, tenant-filtered (for modules that store product ids). */
    @Query("select p from Product p where p.id in :ids")
    java.util.List<Product> withIds(@Param("ids") java.util.Collection<Long> ids);

    /** Products with their units in one query (imports update thousands; no per-product unit queries). */
    @Query("select distinct p from Product p left join fetch p.units where p.sku in :skus")
    java.util.List<Product> findBySkuInWithUnits(@Param("skus") java.util.Collection<String> skus);

    /** Owners of these barcodes, as [barcode, sku] pairs: product barcodes, then unit barcodes. */
    @Query("select p.barcode, p.sku from Product p where p.barcode in :codes")
    java.util.List<Object[]> productBarcodeOwners(@Param("codes") java.util.Collection<String> codes);

    @Query("select u.barcode, u.product.sku from ProductUom u where u.barcode in :codes")
    java.util.List<Object[]> unitBarcodeOwners(@Param("codes") java.util.Collection<String> codes);

    @Query("select u from ProductUom u join fetch u.product where u.barcode = :barcode")
    Optional<ProductUom> findUnitByBarcode(@Param("barcode") String barcode);
}
