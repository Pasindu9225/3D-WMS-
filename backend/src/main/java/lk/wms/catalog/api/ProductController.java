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
import lk.wms.catalog.service.CsvRows;
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
            @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("10000000")
            @jakarta.validation.constraints.Digits(integer = 8, fraction = 3) java.math.BigDecimal reorderPoint,
            @Size(max = 40) @Pattern(regexp = "[^\\p{Cntrl}]*") String category,
            @Min(10) @Max(5000) Integer cartonLengthMm, // v2.1: real carton size for 3D (all three or none)
            @Min(10) @Max(5000) Integer cartonWidthMm,
            @Min(10) @Max(5000) Integer cartonHeightMm,
            @Min(1) @Max(100_000) Integer unitsPerCarton,
            @Valid ProductService.Handling handling, // v2.1: null = ambient, not dangerous, no stack limit
            @NotNull @Size(max = 10) List<@NotNull @Valid UnitRequest> units,
            @Min(0) Integer version) { // required on update

        ProductSpec spec() {
            return new ProductSpec(name, barcode, baseUom, weightG, volumeCm3, trackBatch, trackExpiry, costMinor, reorderPoint, category,
                    units.stream().map(u -> new UnitSpec(u.uom(), u.factor(), u.barcode())).toList(),
                    cartonLengthMm, cartonWidthMm, cartonHeightMm, unitsPerCarton, handling);
        }
    }

    /** One part of the units column: CASE=12 or CASE=12:14791234000018. */
    private static final java.util.regex.Pattern UNIT =
            java.util.regex.Pattern.compile("^\\s*([A-Za-z]+)\\s*=\\s*(\\d{1,7})\\s*(?::\\s*(\\S+))?\\s*$");

    static final List<String> COLUMNS = List.of("sku", "name", "barcode", "base_uom", "weight_kg", "volume_l", "cost",
            "track_batch", "track_expiry", "units", "reorder_point", "category", "carton_cm", "units_per_carton",
            "hs_code", "storage_temp", "hazard_class", "stack_max", "fragile");

    private final ProductService products;
    private final jakarta.validation.Validator validator;
    private final lk.wms.common.web.RateLimiter limiter;

    public ProductController(ProductService products, jakarta.validation.Validator validator,
            lk.wms.common.web.RateLimiter limiter) {
        this.products = products;
        this.validator = validator;
        this.limiter = limiter;
    }

    /**
     * SEC-46: .csv, UTF-8, at most 10 MB and 5,000 rows; every row checked with the same rules as the form;
     * new SKUs created, existing ones updated, all or nothing. units: CASE=12:barcode;PAL=480
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('catalog:manage')")
    public lk.wms.catalog.service.ImportResult importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        CurrentUser cu = CurrentUser.require();
        CsvRows.uploadAllowed(limiter, cu);
        CsvRows csv = new CsvRows(lk.wms.common.Csv.readUpload(file.getOriginalFilename(), file.getBytes()), COLUMNS,
                List.of("sku", "name", "base_uom"));
        List<ProductService.ImportRow> rows = new java.util.ArrayList<>();
        Map<String, String> columnOf = Map.ofEntries(Map.entry("sku", "sku"),
                Map.entry("name", "name"),
                Map.entry("barcode", "barcode"),
                Map.entry("baseUom", "base_uom"),
                Map.entry("weightG", "weight_kg"),
                Map.entry("volumeCm3", "volume_l"),
                Map.entry("costMinor", "cost"),
                Map.entry("units", "units"),
                Map.entry("reorderPoint", "reorder_point"),
                Map.entry("category", "category"),
                Map.entry("cartonLengthMm", "carton_cm"),
                Map.entry("cartonWidthMm", "carton_cm"),
                Map.entry("cartonHeightMm", "carton_cm"),
                Map.entry("unitsPerCarton", "units_per_carton"),
                Map.entry("handling.hsCode", "hs_code"),
                Map.entry("handling.storageTemp", "storage_temp"),
                Map.entry("handling.hazardClass", "hazard_class"),
                Map.entry("handling.stackMax", "stack_max"));
        for (int i = 0; i < csv.size(); i++) {
            int errorsBefore = csv.errors.size();
            Long weight = csv.scaled(i, "weight_kg", 1000);
            Long volume = csv.scaled(i, "volume_l", 1000);
            Long cost = csv.scaled(i, "cost", 100);
            Long reorder = csv.scaled(i, "reorder_point", 1000);
            List<UnitRequest> units = units(csv, i);
            ProductRequest r = new ProductRequest(csv.upper(i, "sku"), csv.text(i, "name"), csv.text(i, "barcode"),
                    csv.upper(i, "base_uom"), toInt(weight), toInt(volume), csv.yesNo(i, "track_batch"),
                    csv.yesNo(i, "track_expiry"), cost, reorder == null ? null : java.math.BigDecimal.valueOf(reorder, 3),
                    csv.text(i, "category"), carton(csv.text(i, "carton_cm"), 0), carton(csv.text(i, "carton_cm"), 1),
                    carton(csv.text(i, "carton_cm"), 2), toInt(csv.scaled(i, "units_per_carton", 1)),
                    new ProductService.Handling(hsCode(csv.text(i, "hs_code")), csv.upper(i, "storage_temp"), csv.text(i, "hazard_class"),
                            toInt(csv.scaled(i, "stack_max", 1)), csv.yesNo(i, "fragile")), units, null);
            if (r.sku() == null) {
                csv.error(i, "sku", "SKU is required");
            } else if (csv.valid(validator, i, r, columnOf) && csv.errors.size() == errorsBefore) {
                rows.add(new ProductService.ImportRow(CsvRows.rowNumber(i), r.sku(), r.spec()));
            }
        }
        return products.importRows(cu, rows, csv.errors);
    }

    /** Example file to fill in; cells are written formula-safe (SEC-46). */
    @GetMapping("/import-template.csv")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ResponseEntity<byte[]> template() {
        String csv = lk.wms.common.Csv.line(COLUMNS)
                + lk.wms.common.Csv.line(List.of("OIL-1L", "Coconut oil 1L", "4791234000011", "EA", "0.95", "1.1", "450.00",
                        "no", "yes", "CASE=12:14791234000018;PAL=480"))
                + lk.wms.common.Csv.line(List.of("RICE-5KG", "Samba rice 5kg", "", "BAG", "5", "6.5", "1250", "yes", "no", ""));
        return CsvRows.csvFile("products-template.csv", csv);
    }

    /** An HS code as people write it ("0902.30", "0902 30 10") to digits only; null when blank. */
    static String hsCode(String text) {
        return text == null ? null : text.replaceAll("[.\\s]", "");
    }

    /** "CASE=12:barcode;PAL=480" into unit requests; a malformed part is a row error. */
    private static List<UnitRequest> units(CsvRows csv, int i) {
        String v = csv.text(i, "units");
        List<UnitRequest> out = new java.util.ArrayList<>();
        if (v == null) {
            return out;
        }
        for (String part : v.split(";")) {
            java.util.regex.Matcher m = UNIT.matcher(part);
            if (part.isBlank()) {
                continue;
            }
            if (!m.matches()) {
                csv.error(i, "units", "Write units like CASE=12 or CASE=12:barcode, separated by ;");
                return List.of();
            }
            out.add(new UnitRequest(m.group(1).toUpperCase(java.util.Locale.ROOT), Integer.parseInt(m.group(2)), m.group(3)));
        }
        return out;
    }

/** One side (0 length, 1 width, 2 height) in mm of a CSV carton size like "40x30x25" (cm); null when blank, 0 when unreadable (then refused). */
    static Integer carton(String text, int side) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*[xX×*]\\s*(\\d+(?:\\.\\d+)?)\\s*[xX×*]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:cm)?\\s*$").matcher(text);
        return m.matches() ? (int) Math.round(Double.parseDouble(m.group(side + 1)) * 10) : 0;
    }

    private static Integer toInt(Long v) {
        return v == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
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
