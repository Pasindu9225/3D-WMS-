package lk.wms.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.service.PutawayRuleService;
import lk.wms.inventory.service.PutawayRuleService.RuleView;
import org.springframework.http.HttpStatus;
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

/** Where goods go (v1.2 task 4). Read: inventory:read or tasks:assign. Change: tasks:assign. */
@RestController
@RequestMapping("/api/v1/putaway-rules")
public class PutawayRuleController {

    public record RuleRequest(@NotBlank @Size(max = 26) String warehouseId, @NotNull @Min(0) @Max(999) Integer priority,
            @Size(max = 64) String sku, @Size(max = 40) @Pattern(regexp = "[^\\p{Cntrl}]*") String category,
            @Size(max = 60) String binCode, @Size(max = 10) String zoneCode, Boolean onlyZone,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,19}") String clientCode) { // v2.1: every product of one client
    }

    public record RuleUpdate(@NotNull @Min(0) Integer version, @NotNull @Min(0) @Max(999) Integer priority, Boolean onlyZone,
            @NotNull Boolean active) {
    }

    private final PutawayRuleService rules;

    public PutawayRuleController(PutawayRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('tasks:assign', 'inventory:read')")
    public List<RuleView> list(@RequestParam @Size(max = 26) String warehouse) {
        return rules.list(CurrentUser.require(), warehouse);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tasks:assign')")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView create(@Valid @RequestBody RuleRequest b) {
        return rules.create(CurrentUser.require(), b.warehouseId(), b.priority(), b.sku(), b.category(), b.clientCode(), b.binCode(), b.zoneCode(),
                Boolean.TRUE.equals(b.onlyZone()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tasks:assign')")
    public RuleView update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody RuleUpdate b) {
        return rules.update(CurrentUser.require(), id, b.version(), b.priority(), Boolean.TRUE.equals(b.onlyZone()), b.active());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tasks:assign')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Size(max = 26) String id) {
        rules.delete(CurrentUser.require(), id);
    }
}
