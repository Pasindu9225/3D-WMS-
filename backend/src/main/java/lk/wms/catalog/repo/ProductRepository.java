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

    Optional<Product> findByPublicId(String publicId);

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
