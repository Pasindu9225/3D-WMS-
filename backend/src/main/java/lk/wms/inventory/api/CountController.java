package lk.wms.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.inventory.domain.CountPlan;
import lk.wms.inventory.service.CountService;
import lk.wms.inventory.service.CountService.CounterView;
import lk.wms.inventory.service.CountService.ReviewView;
import lk.wms.inventory.service.CountService.Summary;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cycle counts (v1.1 task 3). Counting: counts:execute. Starting, reviewing, approving: counts:approve. */
@RestController
@RequestMapping("/api/v1/count-plans")
public class CountController {

    public record CreateRequest(@NotBlank @Size(max = 26) String warehouseId, @NotNull CountPlan.Scope scope,
            @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9]*") String scopeValue) {
    }

    public record CountRequest(@NotBlank @Size(max = 60) String binCode, @NotBlank @Size(max = 64) String code,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty) {
    }

    public record FoundRequest(@NotBlank @Size(max = 60) String binCode, @NotBlank @Size(max = 64) String code,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @Pattern(regexp = "[0-9A-Za-z._/-]{1,40}") String batchNo, LocalDate expiryDate) {
    }

    public record RecountRequest(@NotNull @Min(0) Integer version, @NotNull @Size(min = 1, max = 2000) List<@NotNull Integer> lines) {
    }

    public record VersionRequest(@NotNull @Min(0) Integer version) {
    }

    public record CancelRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 200) String reason) {
    }

    private final CountService counts;

    public CountController(CountService counts) {
        this.counts = counts;
    }

    /** Open counts by default (the counter's to-do list); done=true for finished and cancelled ones. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('counts:execute', 'counts:approve')")
    public PageResponse<Summary> list(@RequestParam(required = false) Boolean done,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        List<CountPlan.Status> statuses = Boolean.TRUE.equals(done) ? List.of(CountPlan.Status.APPROVED, CountPlan.Status.CANCELLED)
                : List.of(CountPlan.Status.OPEN, CountPlan.Status.REVIEW);
        return PageResponse.of(counts.list(CurrentUser.require(), statuses, PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.DESC, "id"))), s -> s);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('counts:approve')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewView create(@Valid @RequestBody CreateRequest body) {
        return counts.create(CurrentUser.require(), body.warehouseId(), body.scope(), body.scopeValue());
    }

    /** Blind: the places to count, never the expected quantities. */
    @GetMapping("/{id}/count")
    @PreAuthorize("hasAuthority('counts:execute')")
    public CounterView forCounter(@PathVariable @Size(max = 26) String id) {
        return counts.forCounter(CurrentUser.require(), id);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('counts:approve')")
    public ReviewView forReview(@PathVariable @Size(max = 26) String id) {
        return counts.forReview(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/lines/{seq}/count")
    @PreAuthorize("hasAuthority('counts:execute')")
    public CounterView count(@PathVariable @Size(max = 26) String id, @PathVariable int seq, @Valid @RequestBody CountRequest body) {
        return counts.count(CurrentUser.require(), id, seq, body.binCode(), body.code(), body.qty());
    }

    @PostMapping("/{id}/found")
    @PreAuthorize("hasAuthority('counts:execute')")
    public CounterView found(@PathVariable @Size(max = 26) String id, @Valid @RequestBody FoundRequest body) {
        return counts.addFound(CurrentUser.require(), id, body.binCode(), body.code(), body.qty(), body.batchNo(), body.expiryDate());
    }

    @PostMapping("/{id}/recount")
    @PreAuthorize("hasAuthority('counts:approve')")
    public ReviewView recount(@PathVariable @Size(max = 26) String id, @Valid @RequestBody RecountRequest body) {
        return counts.recount(CurrentUser.require(), id, body.version(), body.lines());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('counts:approve')")
    public ReviewView approve(@PathVariable @Size(max = 26) String id, @Valid @RequestBody VersionRequest body) {
        return counts.approve(CurrentUser.require(), id, body.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('counts:approve')")
    public ReviewView cancel(@PathVariable @Size(max = 26) String id, @Valid @RequestBody CancelRequest body) {
        return counts.cancel(CurrentUser.require(), id, body.version(), body.reason());
    }
}
