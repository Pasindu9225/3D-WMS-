package lk.wms.outbound.api;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.wms.catalog.service.CsvRows;
import lk.wms.catalog.service.ImportResult;
import lk.wms.common.Csv;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.common.web.RateLimiter;
import lk.wms.outbound.service.DocumentPdf;
import lk.wms.outbound.service.PackService;
import lk.wms.outbound.service.SalesOrderService;
import lk.wms.outbound.service.SalesOrderService.Detail;
import lk.wms.outbound.service.SalesOrderService.ImportLine;
import lk.wms.outbound.service.SalesOrderService.LineSpec;
import lk.wms.outbound.service.SalesOrderService.OrderSpec;
import lk.wms.outbound.service.SalesOrderService.Summary;
import lk.wms.outbound.domain.SalesOrder;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Sales orders (v1.0 task 1). Read: outbound:read; create, edit, cancel, import: outbound:manage. */
@RestController
@RequestMapping("/api/v1/sales-orders")
public class SalesOrderController {

    static final List<String> COLUMNS = List.of("order_ref", "customer_code", "warehouse_code", "requested_date",
            "ship_to", "note", "sku", "uom", "qty");

    public record LineRequest(
            @NotBlank @Size(max = 40) String sku,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{0,9}") String uom,
            @NotNull @DecimalMin(value = "0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty) {

        LineSpec spec() {
            return new LineSpec(sku.trim().toUpperCase(Locale.ROOT), uom, qty);
        }
    }

    public record OrderRequest(
            @NotBlank @Size(max = 20) String customerCode,
            @Size(max = 40) String customerRef,
            @NotBlank @Size(max = 26) String warehouseId,
            @Size(max = 500) String shipTo,
            LocalDate requestedDate,
            @Size(max = 500) String note,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull LineRequest> lines,
            @Min(0) Integer version) { // edits only

        OrderSpec spec() {
            checkDate(requestedDate);
            return new OrderSpec(customerCode.trim().toUpperCase(Locale.ROOT), customerRef, warehouseId, shipTo,
                    requestedDate, note, lines.stream().map(LineRequest::spec).toList());
        }
    }

    /** One CSV row, checked with the same Bean Validation as the API. */
    public record ImportRequest(
            @NotBlank @Size(max = 40) String orderRef,
            @NotBlank @Size(max = 20) String customerCode,
            @NotBlank @Size(max = 20) String warehouseCode,
            @Size(max = 500) String shipTo,
            @Size(max = 500) String note,
            @NotNull @Valid LineRequest line) {
    }

    public record VersionRequest(@NotNull @Min(0) Integer version) {
    }

    public record PackCount(@NotBlank @Size(max = 40) String sku,
            @NotNull @DecimalMin("0") @DecimalMax("10000000") @Digits(integer = 8, fraction = 3) BigDecimal qty) {
    }

    public record PackRequest(@NotNull @Min(0) Integer version, @NotNull @Min(1) @jakarta.validation.constraints.Max(999) Integer cartons,
            @Min(0) @jakarta.validation.constraints.Max(100_000_000) Integer weightG,
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull PackCount> counts) {
    }

    public record CancelRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 200) String reason) {
    }

    private final SalesOrderService orders;
    private final PackService packs;
    private final lk.wms.outbound.service.ShipmentService shipments;
    private final Validator validator;
    private final RateLimiter limiter;

    public SalesOrderController(SalesOrderService orders, PackService packs, lk.wms.outbound.service.ShipmentService shipments,
            Validator validator, RateLimiter limiter) {
        this.orders = orders;
        this.packs = packs;
        this.shipments = shipments;
        this.validator = validator;
        this.limiter = limiter;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('outbound:read')")
    public PageResponse<Summary> list(@RequestParam(required = false) SalesOrder.Status status,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @Size(max = 40) String sort) {
        List<SalesOrder.Status> statuses = status != null ? List.of(status) : List.of(SalesOrder.Status.values());
        return PageResponse.of(orders.list(CurrentUser.require(), statuses, q, PageResponse.request(page, size, sort,
                Map.of("number", "number", "requestedDate", "requestedDate", "customer", "customerName"),
                Sort.by(Sort.Direction.DESC, "id"))), s -> s);
    }

    /** Picked orders waiting at the packing table, oldest first. */
    @GetMapping("/to-pack")
    @PreAuthorize("hasAnyAuthority('packing:execute', 'outbound:read')")
    public PageResponse<Summary> toPack(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(packs.toPack(CurrentUser.require(), PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.ASC, "id"))), s -> s);
    }

    /** Packed orders waiting for a vehicle, oldest first. */
    @GetMapping("/to-dispatch")
    @PreAuthorize("hasAnyAuthority('dispatch:execute', 'outbound:read')")
    public PageResponse<Summary> toDispatch(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(shipments.toDispatch(CurrentUser.require(), PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.ASC, "id"))), s -> s);
    }

    @GetMapping("/{id}/pack")
    @PreAuthorize("hasAnyAuthority('packing:execute', 'outbound:read')")
    public PackService.PackView packView(@PathVariable @Size(max = 26) String id) {
        return packs.view(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/pack")
    @PreAuthorize("hasAuthority('packing:execute')")
    public PackService.PackView pack(@PathVariable @Size(max = 26) String id, @Valid @RequestBody PackRequest body) {
        return packs.finish(CurrentUser.require(), id, body.version(), body.cartons(), body.weightG(),
                body.counts().stream().map(c -> new PackService.Count(c.sku(), c.qty())).toList());
    }

    @GetMapping("/{id}/packing-slip.pdf")
    @PreAuthorize("hasAnyAuthority('packing:execute', 'outbound:read')")
    public ResponseEntity<byte[]> packingSlip(@PathVariable @Size(max = 26) String id) {
        PackService.Papers p = packs.papers(CurrentUser.require(), id);
        return pdf("packing-slip-" + p.number() + ".pdf", DocumentPdf.packingSlip(p));
    }

    @GetMapping("/{id}/delivery-note.pdf")
    @PreAuthorize("hasAnyAuthority('packing:execute', 'outbound:read')")
    public ResponseEntity<byte[]> deliveryNote(@PathVariable @Size(max = 26) String id) {
        PackService.Papers p = packs.papers(CurrentUser.require(), id);
        return pdf(p.deliveryNoteNo() + ".pdf", DocumentPdf.deliveryNote(p));
    }

    /**
     * Opens in the browser's PDF viewer (to print); nosniff comes from the security headers. The site-wide CSP has
     * object-src 'none', which stops the browser's built-in viewer, so a PDF gets its own policy: nothing may load
     * except the PDF itself, and no page may frame it (Spring Security keeps a CSP the response already set).
     */
    private static ResponseEntity<byte[]> pdf(String name, byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Security-Policy", "default-src 'none'; object-src 'self'; frame-ancestors 'none'")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline().filename(name).build().toString())
                .body(bytes);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('outbound:read')")
    public Detail get(@PathVariable @Size(max = 26) String id) {
        return orders.get(CurrentUser.require(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('outbound:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@Valid @RequestBody OrderRequest body) {
        return orders.create(CurrentUser.require(), body.spec());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('outbound:manage')")
    public Detail update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody OrderRequest body) {
        if (body.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "Send the version you edited");
        }
        return orders.update(CurrentUser.require(), id, body.version(), body.spec());
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('outbound:manage')")
    public Detail release(@PathVariable @Size(max = 26) String id, @Valid @RequestBody VersionRequest body) {
        return orders.release(CurrentUser.require(), id, body.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('outbound:manage')")
    public Detail cancel(@PathVariable @Size(max = 26) String id, @Valid @RequestBody CancelRequest body) {
        return orders.cancel(CurrentUser.require(), id, body.version(), body.reason());
    }

    /** SEC-46: .csv, UTF-8, at most 10 MB and 5,000 rows; one row per order line; every row checked; all or nothing. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('outbound:manage')")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        CurrentUser cu = CurrentUser.require();
        CsvRows.uploadAllowed(limiter, cu);
        CsvRows csv = new CsvRows(Csv.readUpload(file.getOriginalFilename(), file.getBytes()), COLUMNS,
                List.of("order_ref", "customer_code", "warehouse_code", "sku", "uom", "qty"));
        Map<String, String> columnOf = Map.of("orderRef", "order_ref", "customerCode", "customer_code",
                "warehouseCode", "warehouse_code", "shipTo", "ship_to", "note", "note", "line.sku", "sku", "line.uom", "uom",
                "line.qty", "qty");
        List<ImportLine> rows = new ArrayList<>();
        for (int i = 0; i < csv.size(); i++) {
            LocalDate date = null;
            String d = csv.text(i, "requested_date");
            try {
                date = d == null ? null : LocalDate.parse(d);
                checkDate(date);
            } catch (DateTimeParseException | ApiException e) {
                csv.error(i, "requested_date", "Use a date like 2026-12-31, within the next two years");
                continue;
            }
            BigDecimal qty = null;
            String q = csv.text(i, "qty");
            try {
                qty = q == null ? null : new BigDecimal(q.replace(",", ""));
            } catch (NumberFormatException e) {
                csv.error(i, "qty", "\"" + q + "\" is not a number");
                continue;
            }
            ImportRequest r = new ImportRequest(csv.text(i, "order_ref"), csv.upper(i, "customer_code"),
                    csv.upper(i, "warehouse_code"), csv.text(i, "ship_to"), csv.text(i, "note"),
                    new LineRequest(csv.upper(i, "sku"), csv.upper(i, "uom"), qty));
            if (csv.valid(validator, i, r, columnOf)) {
                rows.add(new ImportLine(CsvRows.rowNumber(i), r.orderRef(), r.customerCode(), r.warehouseCode(), date,
                        r.shipTo(), r.note(), r.line().spec()));
            }
        }
        return orders.importRows(cu, rows, csv.errors);
    }

    /** Example file to fill in; cells are written formula-safe (SEC-46). */
    @GetMapping("/import-template.csv")
    @PreAuthorize("hasAuthority('outbound:read')")
    public ResponseEntity<byte[]> template() {
        String csv = Csv.line(COLUMNS)
                + Csv.line(List.of("KSM-4471", "CUS-001", "MAIN", "2026-12-20", "45 Peradeniya Road, Kandy", "Deliver before noon", "OIL-1L", "CASE", "10"))
                + Csv.line(List.of("KSM-4471", "CUS-001", "MAIN", "2026-12-20", "", "", "RICE-5KG", "BAG", "40"));
        return CsvRows.csvFile("sales-orders-template.csv", csv);
    }

    private static void checkDate(LocalDate date) {
        if (date != null && (date.isBefore(LocalDate.now().minusYears(1)) || date.isAfter(LocalDate.now().plusYears(2)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_DATE", "Pick a date within the next two years");
        }
    }
}
