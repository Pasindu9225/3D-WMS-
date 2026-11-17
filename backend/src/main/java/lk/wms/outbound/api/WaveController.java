package lk.wms.outbound.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.outbound.service.WaveService;
import lk.wms.outbound.service.WaveService.Summary;
import lk.wms.outbound.service.WaveService.WaveView;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Batch picking (v1.2 task 1). Making and dissolving waves: tasks:assign. Seeing and walking them: picking. */
@RestController
@RequestMapping("/api/v1/pick-waves")
public class WaveController {

    public record CreateRequest(@NotBlank @Size(max = 26) String warehouseId,
            @NotNull @Size(min = 2, max = 20) List<@NotBlank @Size(max = 26) String> listIds, @Size(max = 26) String pickerId) {
    }

    public record CutOffRequest(@NotBlank @Size(max = 26) String warehouseId, @NotNull LocalDate cutOff,
            @NotNull @Min(2) @Max(20) Integer maxOrders, @Size(max = 26) String pickerId) {
    }

    private final WaveService waves;

    public WaveController(WaveService waves) {
        this.waves = waves;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('picking:execute', 'outbound:read')")
    public PageResponse<Summary> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(waves.list(CurrentUser.require(), PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.ASC, "id"))), s -> s);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tasks:assign')")
    @ResponseStatus(HttpStatus.CREATED)
    public WaveView create(@Valid @RequestBody CreateRequest body) {
        return waves.create(CurrentUser.require(), body.warehouseId(), body.listIds(), body.pickerId());
    }

    @PostMapping("/by-cut-off")
    @PreAuthorize("hasAuthority('tasks:assign')")
    @ResponseStatus(HttpStatus.CREATED)
    public WaveView byCutOff(@Valid @RequestBody CutOffRequest body) {
        return waves.byCutOff(CurrentUser.require(), body.warehouseId(), body.cutOff(), body.maxOrders(), body.pickerId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('picking:execute', 'outbound:read')")
    public WaveView get(@PathVariable @Size(max = 26) String id) {
        return waves.get(CurrentUser.require(), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tasks:assign')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dissolve(@PathVariable @Size(max = 26) String id) {
        waves.dissolve(CurrentUser.require(), id);
    }
}
