package lk.wms.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.domain.Product.UnitSpec;
import lk.wms.catalog.service.ProductService;
import lk.wms.catalog.service.ProductService.ProductSpec;
import lk.wms.catalog.service.ProductService.ProductView;
import lk.wms.catalog.service.ProductService.ScanView;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Product master (v0.4 task 1). Read: catalog:read; changes: catalog:manage. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    /** Barcodes: EAN/UPC digits or Code 128 style letters, digits and dashes. */
    static final String BARCODE = "[0-9A-Za-z-]{1,64}";
    static final String UOM = "[A-Z]{1,10}";

    public record UnitRequest(
            @NotNull @Pattern(regexp = UOM) String uom,
            @Min(2) @Max(1_000_000) int factor,
            @Pattern(regexp = BARCODE) String barcode) {
    }

    public record ProductRequest(
            @Pattern(regexp = "[A-Z0-9][A-Z0-9._-]{0,39}") String sku, // only used on create
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = BARCODE) String barcode,
            @NotNull @Pattern(regexp = UOM) String baseUom,
            @Min(0) @Max(100_000_000) Integer weightG,
            @Min(0) @Max(100_000_000) Integer volumeCm3,
            boolean trackBatch,
            boolean trackExpiry,
            @Min(0) @Max(1_000_000_000_000L) Long costMinor,
            @NotNull @Size(max = 10) List<@NotNull @Valid UnitRequest> units,
            @Min(0) Integer version) { // required on update

        ProductSpec spec() {
            return new ProductSpec(name, barcode, baseUom, weightG, volumeCm3, trackBatch, trackExpiry, costMinor,
                    units.stream().map(u -> new UnitSpec(u.uom(), u.factor(), u.barcode())).toList());
        }
    }

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public PageResponse<ProductView> list(@RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @Size(max = 40) String sort) {
        return PageResponse.of(products.list(q, PageResponse.request(page, size, sort,
                Map.of("sku", "sku", "name", "name"), Sort.by("sku"))), p -> p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ProductView get(@PathVariable @Size(max = 26) String id) {
        return products.get(id);
    }

    @GetMapping("/by-barcode/{barcode}")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ScanView byBarcode(@PathVariable @Pattern(regexp = BARCODE) String barcode) {
        return products.byBarcode(barcode);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody ProductRequest body) {
        if (body.sku() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SKU_REQUIRED", "Give the product a SKU");
        }
        return products.create(CurrentUser.require(), body.sku(), body.spec());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ProductView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody ProductRequest body) {
        if (body.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED",
                    "Send the version you edited");
        }
        return products.update(CurrentUser.require(), id, body.version(), body.spec());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ResponseEntity<Void> archive(@PathVariable @Size(max = 26) String id) {
        products.archive(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    /** SEC-40..45: JPEG or PNG up to 5 MB, re-encoded before it is stored. */
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ProductView setImage(@PathVariable @Size(max = 26) String id, @RequestParam("file") MultipartFile file)
            throws IOException {
        return products.setImage(CurrentUser.require(), id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @DeleteMapping("/{id}/image")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ProductView removeImage(@PathVariable @Size(max = 26) String id) {
        return products.removeImage(CurrentUser.require(), id);
    }

    /** Served only through the product (tenant check), never as a static file (SEC-44); nosniff is global. */
    @GetMapping("/{id}/image")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ResponseEntity<byte[]> image(@PathVariable @Size(max = 26) String id) {
        ProductService.Image img = products.image(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(img.sku() + ".jpg").build().toString())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(img.bytes());
    }
}
