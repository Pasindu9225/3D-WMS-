package lk.wms.catalog.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lk.wms.catalog.domain.FileObject;
import lk.wms.catalog.domain.Product;
import lk.wms.catalog.domain.Product.UnitSpec;
import lk.wms.catalog.domain.ProductUom;
import lk.wms.catalog.repo.FileObjectRepository;
import lk.wms.catalog.repo.ProductRepository;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.RateLimiter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The company's product master (v0.4 task 1). Products are shared by all its warehouses. */
@Service
public class ProductService {

    public record UnitView(String uom, int factor, String barcode) {
    }

    public record ProductView(String id, String sku, String barcode, String name, String baseUom, Integer weightG,
            Integer volumeCm3, boolean trackBatch, boolean trackExpiry, Long costMinor, boolean hasImage,
            List<UnitView> units, int version) {
    }

    /** Fields the user sets; the SKU is fixed once created (labels, orders and imports refer to it). */
    public record ProductSpec(String name, String barcode, String baseUom, Integer weightG, Integer volumeCm3,
            boolean trackBatch, boolean trackExpiry, Long costMinor, List<UnitSpec> units) {
    }

    /** A barcode lookup (scanning): the product and how many base units one scan of it means. */
    public record ScanView(ProductView product, String uom, int factor) {
    }

    /** The stored picture of a product, ready to send. */
    public record Image(byte[] bytes, String contentType, String sku) {
    }

    private final ProductRepository products;
    private final FileObjectRepository files;
    private final ImageStore images;
    private final RateLimiter limiter;
    private final AuditService audit;

    public ProductService(ProductRepository products, FileObjectRepository files, ImageStore images, RateLimiter limiter,
            AuditService audit) {
        this.products = products;
        this.files = files;
        this.images = images;
        this.limiter = limiter;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<ProductView> list(String query, Pageable pageable) {
        String q = query == null ? "" : query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return products.search(q, pageable).map(ProductService::view);
    }

    @Transactional(readOnly = true)
    public ProductView get(String publicId) {
        return view(find(publicId));
    }

    @Transactional
    public ProductView create(CurrentUser cu, String sku, ProductSpec s) {
        checkBarcodes(s, null);
        Product p = new Product(sku);
        apply(p, s);
        try {
            p = products.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw conflict();
        }
        audit.record(cu.tenantId(), cu.userId(), "PRODUCT_CREATED", "product", p.getPublicId(), null, view(p));
        return view(p);
    }

    /** version must match the stored one, so two people editing the same product cannot overwrite each other. */
    @Transactional
    public ProductView update(CurrentUser cu, String publicId, int version, ProductSpec s) {
        Product p = find(publicId);
        if (p.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_CHANGED", "Someone else changed this product; reload it");
        }
        checkBarcodes(s, p);
        ProductView before = view(p);
        apply(p, s);
        try {
            products.flush();
        } catch (DataIntegrityViolationException e) {
            throw conflict();
        }
        audit.record(cu.tenantId(), cu.userId(), "PRODUCT_UPDATED", "product", p.getPublicId(), before, view(p));
        return view(p);
    }

    @Transactional
    public void archive(CurrentUser cu, String publicId) {
        Product p = find(publicId);
        p.archive();
        audit.record(cu.tenantId(), cu.userId(), "PRODUCT_ARCHIVED", "product", p.getPublicId(), view(p), null);
    }

    /**
     * Sets the product picture (SEC-40..45). The upload is checked and re-encoded by ImageStore; the previous
     * picture is removed from disk only after the change is committed.
     * ponytail: a new file whose transaction then fails stays on disk; add a sweep of unreferenced files if it matters.
     */
    @Transactional
    public ProductView setImage(CurrentUser cu, String publicId, String filename, String contentType, byte[] bytes) {
        if (limiter.tryConsume(RateLimiter.Scope.UPLOAD, Long.toString(cu.userId())) > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS", "Too many uploads; wait a minute");
        }
        Product p = find(publicId);
        ImageStore.Stored stored = images.store(cu.tenantId(), filename, contentType, bytes);
        FileObject file = files.save(new FileObject("image/jpeg", stored.sizeBytes(), stored.width(), stored.height(),
                stored.path(), cu.userId()));
        dropImage(p);
        p.setImageFileId(file.getId());
        audit.record(cu.tenantId(), cu.userId(), "PRODUCT_IMAGE_SET", "product", p.getPublicId(), null,
                java.util.Map.of("bytes", stored.sizeBytes(), "width", stored.width(), "height", stored.height()));
        products.flush(); // bump the version now, so the answer carries the version the next save must send
        return view(p);
    }

    @Transactional
    public ProductView removeImage(CurrentUser cu, String publicId) {
        Product p = find(publicId);
        if (p.getImageFileId() != null) {
            dropImage(p);
            p.setImageFileId(null);
            audit.record(cu.tenantId(), cu.userId(), "PRODUCT_IMAGE_REMOVED", "product", p.getPublicId(), null, null);
            products.flush();
        }
        return view(p);
    }

    /** The picture, only through its product, so the tenant filter decides who may see it (SEC-44). */
    @Transactional(readOnly = true)
    public Image image(String publicId) {
        Product p = find(publicId);
        FileObject file = p.getImageFileId() == null ? null : files.findLive(p.getImageFileId()).orElse(null);
        if (file == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "This product has no picture");
        }
        return new Image(images.read(file.getPath()), file.getContentType(), p.getSku());
    }

    private void dropImage(Product p) {
        if (p.getImageFileId() == null) {
            return;
        }
        files.findLive(p.getImageFileId()).ifPresent(old -> {
            old.delete();
            String path = old.getPath();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    images.delete(path);
                }
            });
        });
    }

    /** Scanning: a product barcode counts 1 base unit, a case barcode counts its factor. */
    @Transactional(readOnly = true)
    public ScanView byBarcode(String barcode) {
        Product p = products.findByBarcode(barcode).orElse(null);
        if (p != null) {
            return new ScanView(view(p), p.getBaseUom(), 1);
        }
        ProductUom u = products.findUnitByBarcode(barcode)
                .filter(x -> products.findByPublicId(x.getProduct().getPublicId()).isPresent()) // not archived
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BARCODE_NOT_FOUND", "No product has this barcode"));
        return new ScanView(view(u.getProduct()), u.getUom(), u.getFactor());
    }

    /**
     * One barcode means one thing: unique across products and their units (the database enforces each table;
     * this checks across them and within the request), so a scan is never ambiguous.
     */
    private void checkBarcodes(ProductSpec s, Product self) {
        List<String> codes = new ArrayList<>();
        if (s.barcode() != null) {
            codes.add(s.barcode());
        }
        s.units().stream().map(UnitSpec::barcode).filter(Objects::nonNull).forEach(codes::add);
        Set<String> seen = new HashSet<>();
        for (String code : codes) {
            if (!seen.add(code)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BARCODE_REPEATED", "Barcode " + code + " is used twice here");
            }
            Product other = products.findByBarcode(code).orElse(null);
            ProductUom otherUnit = products.findUnitByBarcode(code).orElse(null);
            boolean mine = (other != null && self != null && other.getId().equals(self.getId()))
                    || (otherUnit != null && self != null && otherUnit.getProduct().getId().equals(self.getId()));
            if ((other != null || otherUnit != null) && !mine) {
                throw conflict();
            }
        }
        Set<String> uoms = new HashSet<>();
        for (UnitSpec u : s.units()) {
            if (u.uom().equals(s.baseUom()) || !uoms.add(u.uom())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UOM_REPEATED", "Unit " + u.uom() + " is listed twice");
            }
        }
    }

    private static void apply(Product p, ProductSpec s) {
        p.set(s.name().trim(), s.barcode(), s.baseUom(), s.weightG(), s.volumeCm3(), s.trackBatch(), s.trackExpiry(),
                s.costMinor());
        p.setUnits(s.units());
    }

    private Product find(String publicId) {
        return products.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found"));
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "PRODUCT_CONFLICT",
                "Another product already uses this SKU or barcode (archived products keep theirs)");
    }

    static ProductView view(Product p) {
        return new ProductView(p.getPublicId(), p.getSku(), p.getBarcode(), p.getName(), p.getBaseUom(), p.getWeightG(),
                p.getVolumeCm3(), p.isTrackBatch(), p.isTrackExpiry(), p.getCostMinor(), p.getImageFileId() != null,
                p.getUnits().stream().map(u -> new UnitView(u.getUom(), u.getFactor(), u.getBarcode())).toList(),
                p.getVersion());
    }
}
