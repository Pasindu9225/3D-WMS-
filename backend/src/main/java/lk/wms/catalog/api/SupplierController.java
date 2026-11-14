package lk.wms.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.domain.Partner;
import lk.wms.catalog.service.ImportResult;
import lk.wms.catalog.service.PartnerService;
import lk.wms.catalog.service.PartnerService.ImportRow;
import lk.wms.catalog.service.PartnerService.PartnerSpec;
import lk.wms.catalog.service.PartnerService.PartnerView;
import lk.wms.common.Csv;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.common.web.RateLimiter;
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

/** Suppliers (v0.4 task 2). Read: catalog:read; changes and imports: catalog:manage. */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    static final List<String> COLUMNS = List.of("code", "name", "contact", "phone", "email", "address");

    public record SupplierRequest(
            @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,19}") String code, // only used on create
            @NotBlank @Size(max = 200) String name,
            @Size(max = 120) String contactName,
            @Pattern(regexp = "[0-9 +()-]{3,30}") String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 500) String address,
            @Min(0) Integer version) {

        PartnerSpec spec() {
            return new PartnerSpec(name, contactName, phone, email, address);
        }
    }

    private final PartnerService partners;
    private final Validator validator;
    private final RateLimiter limiter;

    public SupplierController(PartnerService partners, Validator validator, RateLimiter limiter) {
        this.partners = partners;
        this.validator = validator;
        this.limiter = limiter;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public PageResponse<PartnerView> list(@RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @Size(max = 40) String sort) {
        return PageResponse.of(partners.list(Partner.Type.SUPPLIER, q, PageResponse.request(page, size, sort,
                Map.of("code", "code", "name", "name"), Sort.by("code"))), p -> p);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerView create(@Valid @RequestBody SupplierRequest body) {
        if (body.code() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED", "Give the supplier a code");
        }
        return partners.create(CurrentUser.require(), Partner.Type.SUPPLIER, body.code(), body.spec());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public PartnerView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody SupplierRequest body) {
        if (body.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "Send the version you edited");
        }
        return partners.update(CurrentUser.require(), Partner.Type.SUPPLIER, id, body.version(), body.spec());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ResponseEntity<Void> archive(@PathVariable @Size(max = 26) String id) {
        partners.archive(CurrentUser.require(), Partner.Type.SUPPLIER, id);
        return ResponseEntity.noContent().build();
    }

    /** SEC-46: .csv, UTF-8, at most 10 MB and 5,000 rows; every row checked; all or nothing. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        CurrentUser cu = CurrentUser.require();
        CsvRows.uploadAllowed(limiter, cu);
        CsvRows csv = new CsvRows(Csv.readUpload(file.getOriginalFilename(), file.getBytes()), COLUMNS, List.of("code", "name"));
        List<ImportRow> rows = new ArrayList<>();
        Map<String, String> columnOf = Map.of("code", "code", "name", "name", "contactName", "contact", "phone", "phone",
                "email", "email", "address", "address");
        for (int i = 0; i < csv.size(); i++) {
            SupplierRequest r = new SupplierRequest(csv.upper(i, "code"), csv.text(i, "name"), csv.text(i, "contact"),
                    csv.text(i, "phone"), csv.text(i, "email"), csv.text(i, "address"), null);
            if (r.code() == null) {
                csv.error(i, "code", "Code is required");
            } else if (csv.valid(validator, i, r, columnOf)) {
                rows.add(new ImportRow(CsvRows.rowNumber(i), r.code(), r.spec()));
            }
        }
        return partners.importRows(cu, Partner.Type.SUPPLIER, rows, csv.errors);
    }

    /** Example file to fill in; cells are written formula-safe (SEC-46). */
    @GetMapping("/import-template.csv")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ResponseEntity<byte[]> template() {
        String csv = Csv.line(COLUMNS) + Csv.line(List.of("SUP-001", "Lanka Oils (Pvt) Ltd", "Nimal Perera", "+94 11 234 5678",
                "orders@lankaoils.lk", "12 Galle Road, Colombo 03"));
        return CsvRows.csvFile("suppliers-template.csv", csv);
    }
}
