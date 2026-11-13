package lk.wms.layout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.wms.auth.service.UserService.UserView;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.layout.service.WarehouseService;
import lk.wms.layout.service.WarehouseService.WarehouseView;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private static final Map<String, String> SORTS = Map.of("code", "code", "name", "name", "createdAt", "createdAt");

    /** Sizes in metres as people think of them (max 3 decimals = millimetres); stored in mm. */
    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,19}") String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull @DecimalMin("1") @DecimalMax("1000") @Digits(integer = 4, fraction = 3) BigDecimal widthM,
            @NotNull @DecimalMin("1") @DecimalMax("1000") @Digits(integer = 4, fraction = 3) BigDecimal lengthM,
            @NotNull @DecimalMin("2") @DecimalMax("40") @Digits(integer = 2, fraction = 3) BigDecimal wallHeightM) {
    }

    public record UpdateRequest(
            @Size(min = 1, max = 120) String name,
            @DecimalMin("2") @DecimalMax("40") @Digits(integer = 2, fraction = 3) BigDecimal wallHeightM) {
    }

    public record AccessRequest(@NotNull @Size(max = 500) List<@NotBlank @Size(max = 26) String> users) {
    }

    private final WarehouseService warehouses;

    public WarehouseController(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('warehouses:read')")
    public PageResponse<WarehouseView> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return PageResponse.of(warehouses.list(CurrentUser.require(),
                PageResponse.request(page, size, sort, SORTS, Sort.by("code", "id"))), w -> w);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('warehouses:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public WarehouseView create(@Valid @RequestBody CreateRequest body) {
        return warehouses.create(CurrentUser.require(), body.code(), body.name(), mm(body.widthM()), mm(body.lengthM()),
                mm(body.wallHeightM()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouses:read')")
    public WarehouseView get(@PathVariable @Size(max = 26) String id) {
        return warehouses.get(CurrentUser.require(), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public WarehouseView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody UpdateRequest body) {
        return warehouses.update(CurrentUser.require(), id, body.name(),
                body.wallHeightM() == null ? null : mm(body.wallHeightM()));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public ResponseEntity<Void> archive(@PathVariable @Size(max = 26) String id) {
        warehouses.archive(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/users")
    @PreAuthorize("hasAuthority('users:read')")
    public List<UserView> users(@PathVariable @Size(max = 26) String id) {
        return warehouses.assignedUsers(CurrentUser.require(), id);
    }

    /** Who may work in this warehouse. A user-management action, so it needs users:manage. */
    @PutMapping("/{id}/users")
    @PreAuthorize("hasAuthority('users:manage')")
    public List<UserView> setUsers(@PathVariable @Size(max = 26) String id, @Valid @RequestBody AccessRequest body) {
        return warehouses.setAssignedUsers(CurrentUser.require(), id, body.users());
    }

    private static int mm(BigDecimal metres) {
        return metres.movePointRight(3).intValueExact();
    }
}
