package lk.wms.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.inventory.service.RefillService;
import lk.wms.inventory.service.RefillService.RuleView;
import lk.wms.inventory.service.RefillService.TaskView;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Keeping pick bins full (v1.2 task 3). Rules: tasks:assign. Doing refills: inventory:transfer. */
@RestController
public class RefillController {

    public record RuleRequest(@NotBlank @Size(max = 26) String warehouseId, @NotBlank @Size(max = 60) String binCode,
            @NotBlank @Size(max = 64) String sku,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal minQty,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal maxQty) {
    }

    public record RuleUpdate(@NotNull @Min(0) Integer version,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal minQty,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal maxQty,
            @NotNull Boolean active) {
    }

    public record CheckRequest(@NotBlank @Size(max = 26) String warehouseId) {
    }

    public record ConfirmRequest(@NotBlank @Size(max = 60) String fromBinCode, @NotBlank @Size(max = 60) String toBinCode,
            @NotBlank @Size(max = 64) String code,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty) {
    }

    private final RefillService refills;

    public RefillController(RefillService refills) {
        this.refills = refills;
    }

    @GetMapping("/api/v1/refill-rules")
    @PreAuthorize("hasAnyAuthority('tasks:assign', 'inventory:read')")
    public List<RuleView> rules(@RequestParam @Size(max = 26) String warehouse) {
        return refills.rules(CurrentUser.require(), warehouse);
    }

    @PostMapping("/api/v1/refill-rules")
    @PreAuthorize("hasAuthority('tasks:assign')")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView create(@Valid @RequestBody RuleRequest body) {
        return refills.create(CurrentUser.require(), body.warehouseId(), body.binCode(), body.sku(), body.minQty(), body.maxQty());
    }

    @PutMapping("/api/v1/refill-rules/{id}")
    @PreAuthorize("hasAuthority('tasks:assign')")
    public RuleView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody RuleUpdate body) {
        return refills.update(CurrentUser.require(), id, body.version(), body.minQty(), body.maxQty(), body.active());
    }

    /** Makes tasks now for every rule below min (e.g. right after setting rules). */
    @PostMapping("/api/v1/refill-rules/check")
    @PreAuthorize("hasAuthority('tasks:assign')")
    public Map<String, Integer> check(@Valid @RequestBody CheckRequest body) {
        return Map.of("made", refills.checkNow(CurrentUser.require(), body.warehouseId()));
    }

    @GetMapping("/api/v1/refill-tasks")
    @PreAuthorize("hasAnyAuthority('inventory:transfer', 'tasks:read')")
    public PageResponse<TaskView> tasks(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(refills.openTasks(CurrentUser.require(), PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.ASC, "id"))), s -> s);
    }

    @GetMapping("/api/v1/refill-tasks/{id}")
    @PreAuthorize("hasAnyAuthority('inventory:transfer', 'tasks:read')")
    public TaskView task(@PathVariable @Size(max = 26) String id) {
        return refills.get(CurrentUser.require(), id);
    }

    @PostMapping("/api/v1/refill-tasks/{id}/confirm")
    @PreAuthorize("hasAuthority('inventory:transfer')")
    public TaskView confirm(@PathVariable @Size(max = 26) String id, @Valid @RequestBody ConfirmRequest body) {
        return refills.confirm(CurrentUser.require(), id, body.fromBinCode(), body.toBinCode(), body.code(), body.qty());
    }

    @PostMapping("/api/v1/refill-tasks/{id}/cancel")
    @PreAuthorize("hasAuthority('tasks:assign')")
    public TaskView cancel(@PathVariable @Size(max = 26) String id) {
        return refills.cancel(CurrentUser.require(), id);
    }
}
