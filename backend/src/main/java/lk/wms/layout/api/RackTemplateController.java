package lk.wms.layout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.layout.service.RackTemplateService;
import lk.wms.layout.service.RackTemplateService.TemplateSpec;
import lk.wms.layout.service.RackTemplateService.TemplateView;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/rack-templates")
public class RackTemplateController {

    private static final Map<String, String> SORTS = Map.of("code", "code", "name", "name");

    /** Millimetres and grams. Limits cover hand shelving (300 mm) up to high-bay pallet racking (40 m). */
    public record TemplateRequest(
            @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,19}") String code, // only used on create
            @NotBlank @Size(max = 80) String name,
            @Min(300) @Max(30_000) int widthMm,
            @Min(300) @Max(5_000) int depthMm,
            @Min(500) @Max(40_000) int heightMm,
            @Min(1) @Max(20) int levels,
            @Min(1) @Max(50) int binsPerLevel,
            @Min(1) @Max(100_000_000) long maxWeightPerLevelG,
            @Pattern(regexp = "PALLET|SHELF|FLOOR") String style, // optional: classic pallet racking; FLOOR = pallets stacked on the floor
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String uprightColor,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String beamColor) {

        TemplateSpec spec() {
            return new TemplateSpec(name, widthMm, depthMm, heightMm, levels, binsPerLevel, maxWeightPerLevelG, style, uprightColor, beamColor);
        }
    }

    private final RackTemplateService templates;

    public RackTemplateController(RackTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('warehouses:read')")
    public PageResponse<TemplateView> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return PageResponse.of(templates.list(PageResponse.request(page, size, sort, SORTS, Sort.by("code", "id"))), t -> t);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('warehouses:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView create(@Valid @RequestBody TemplateRequest body) {
        if (body.code() == null) {
            throw new lk.wms.common.errors.ApiException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED", "A code is required");
        }
        return templates.create(CurrentUser.require(), body.code(), body.spec());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public TemplateView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody TemplateRequest body) {
        return templates.update(CurrentUser.require(), id, body.spec());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public ResponseEntity<Void> archive(@PathVariable @Size(max = 26) String id) {
        templates.archive(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }
}
