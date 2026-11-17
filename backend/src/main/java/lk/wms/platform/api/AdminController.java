package lk.wms.platform.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.platform.service.PlatformService;
import lk.wms.tenant.service.PlatformTenants;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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

/** Super Admin portal (v2.0). Read: platform:read. Change: platform:manage. Only the SUPER_ADMIN role holds these. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    static final String PLAN_CODE = "[A-Za-z0-9_-]{2,30}";

    public record CreateCompany(@NotBlank @Size(max = 150) String name, @NotBlank @Pattern(regexp = PLAN_CODE) String planCode,
            @NotBlank @Email @Size(max = 190) String adminEmail, @NotBlank @Size(max = 120) String adminName) {
    }

    public record PlanChange(@NotNull @Min(0) Integer version, @NotBlank @Pattern(regexp = PLAN_CODE) String planCode,
            @NotNull @Min(1) @Max(1000) Integer maxWarehouses) {
    }

    public record Suspend(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 200) String reason) {
    }

    public record Version(@NotNull @Min(0) Integer version) {
    }

    public record PlanRequest(@NotBlank @Pattern(regexp = PLAN_CODE) String code, @NotBlank @Size(max = 80) String name,
            @NotNull @Min(0) @Max(100_000_000_000L) Long pricePerWarehouseMinor, @NotNull @Min(1) @Max(1000) Integer defaultMaxWarehouses) {
    }

    public record PlanUpdate(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 80) String name,
            @NotNull @Min(0) @Max(100_000_000_000L) Long pricePerWarehouseMinor, @NotNull @Min(1) @Max(1000) Integer defaultMaxWarehouses,
            @NotNull Boolean active) {
    }

    private final PlatformService platform;
    private final PlatformTenants tenants;

    public AdminController(PlatformService platform, PlatformTenants tenants) {
        this.platform = platform;
        this.tenants = tenants;
    }

    @GetMapping("/tenants")
    @PreAuthorize("hasAuthority('platform:read')")
    public PageResponse<PlatformService.Row> tenants(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(platform.list(q, status, PageResponse.request(page, size, sort,
                Map.of("name", "name", "createdAt", "createdAt", "status", "status"), Sort.by(Sort.Direction.DESC, "createdAt"))), r -> r);
    }

    @GetMapping("/tenants/{id}")
    @PreAuthorize("hasAuthority('platform:read')")
    public PlatformService.Detail tenant(@PathVariable @Size(max = 26) String id) {
        return platform.get(id);
    }

    @PostMapping("/tenants")
    @PreAuthorize("hasAuthority('platform:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformService.Created create(@Valid @RequestBody CreateCompany b) {
        return platform.create(CurrentUser.require(), b.name(), b.planCode(), b.adminEmail(), b.adminName());
    }

    @PutMapping("/tenants/{id}/plan")
    @PreAuthorize("hasAuthority('platform:manage')")
    public PlatformService.Detail plan(@PathVariable @Size(max = 26) String id, @Valid @RequestBody PlanChange b) {
        return platform.setPlan(CurrentUser.require(), id, b.version(), b.planCode(), b.maxWarehouses());
    }

    @PostMapping("/tenants/{id}/suspend")
    @PreAuthorize("hasAuthority('platform:manage')")
    public PlatformService.Detail suspend(@PathVariable @Size(max = 26) String id, @Valid @RequestBody Suspend b) {
        return platform.suspend(CurrentUser.require(), id, b.version(), b.reason());
    }

    @PostMapping("/tenants/{id}/reactivate")
    @PreAuthorize("hasAuthority('platform:manage')")
    public PlatformService.Detail reactivate(@PathVariable @Size(max = 26) String id, @Valid @RequestBody Version b) {
        return platform.reactivate(CurrentUser.require(), id, b.version());
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('platform:read')")
    public List<PlatformTenants.PlanView> plans() {
        return tenants.plans();
    }

    @PostMapping("/plans")
    @PreAuthorize("hasAuthority('platform:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformTenants.PlanView createPlan(@Valid @RequestBody PlanRequest b) {
        return tenants.createPlan(b.code(), b.name(), b.pricePerWarehouseMinor(), b.defaultMaxWarehouses());
    }

    @PutMapping("/plans/{code}")
    @PreAuthorize("hasAuthority('platform:manage')")
    public PlatformTenants.PlanView updatePlan(@PathVariable @Pattern(regexp = PLAN_CODE) String code, @Valid @RequestBody PlanUpdate b) {
        return tenants.updatePlan(code, b.version(), b.name(), b.pricePerWarehouseMinor(), b.defaultMaxWarehouses(), b.active());
    }
}
