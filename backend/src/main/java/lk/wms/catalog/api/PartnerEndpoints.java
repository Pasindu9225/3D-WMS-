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
import lk.wms.catalog.service.CsvRows;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * Suppliers and customers share one table and one set of endpoints; {@link SupplierController} and
 * {@link CustomerController} only fix the type and the URL. Read: catalog:read; changes and imports: catalog:manage.
 */
public abstract class PartnerEndpoints {

    static final List<String> COLUMNS = List.of("code", "name", "contact", "phone", "email", "address");

    public record PartnerRequest(
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

    private final Partner.Type type;
    private final List<String> example;
    private final PartnerService partners;
    private final Validator validator;
    private final RateLimiter limiter;

    protected PartnerEndpoints(Partner.Type type, List<String> example, PartnerService partners, Validator validator,
            RateLimiter limiter) {
        this.type = type;
        this.example = example;
        this.partners = partners;
        this.validator = validator;
        this.limiter = limiter;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public PageResponse<PartnerView> list(@RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @Size(max = 40) String sort) {
        return PageResponse.of(partners.list(type, q, PageResponse.request(page, size, sort,
                Map.of("code", "code", "name", "name"), Sort.by("code"))), p -> p);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerView create(@Valid @RequestBody PartnerRequest body) {
        if (body.code() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED", "Give it a code");
        }
        return partners.create(CurrentUser.require(), type, body.code(), body.spec());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public PartnerView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody PartnerRequest body) {
        if (body.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "Send the version you edited");
        }
        return partners.update(CurrentUser.require(), type, id, body.version(), body.spec());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('catalog:manage')")
    public ResponseEntity<Void> archive(@PathVariable @Size(max = 26) String id) {
        partners.archive(CurrentUser.require(), type, id);
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
            PartnerRequest r = new PartnerRequest(csv.upper(i, "code"), csv.text(i, "name"), csv.text(i, "contact"),
                    csv.text(i, "phone"), csv.text(i, "email"), csv.text(i, "address"), null);
            if (r.code() == null) {
                csv.error(i, "code", "Code is required");
            } else if (csv.valid(validator, i, r, columnOf)) {
                rows.add(new ImportRow(CsvRows.rowNumber(i), r.code(), r.spec()));
            }
        }
        return partners.importRows(cu, type, rows, csv.errors);
    }

    /** Example file to fill in; cells are written formula-safe (SEC-46). */
    @GetMapping("/import-template.csv")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ResponseEntity<byte[]> template() {
        return CsvRows.csvFile(type.name().toLowerCase() + "s-template.csv", Csv.line(COLUMNS) + Csv.line(example));
    }
}
