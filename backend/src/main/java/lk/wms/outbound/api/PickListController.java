package lk.wms.outbound.api;

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
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.outbound.domain.PickList;
import lk.wms.outbound.service.PickService;
import lk.wms.outbound.service.PickService.Detail;
import lk.wms.outbound.service.PickService.ShortReason;
import lk.wms.outbound.service.PickService.Summary;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pick lists (v1.0 task 3). Read: picking:execute or outbound:read; pick and short: picking:execute. */
@RestController
@RequestMapping("/api/v1/pick-lists")
public class PickListController {

    public record ConfirmRequest(
            @NotBlank @Size(max = 60) String binCode,
            @NotBlank @Size(max = 64) String code,
            @NotNull @DecimalMin(value = "0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty) {
    }

    public record ShortRequest(@NotNull ShortReason reason) {
    }

    private final PickService picks;

    public PickListController(PickService picks) {
        this.picks = picks;
    }

    /** Open = not done yet (OPEN or IN_PROGRESS), the picker's to-do list; done=true for finished ones. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('picking:execute', 'outbound:read')")
    public PageResponse<Summary> list(@RequestParam(required = false) Boolean done,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        List<PickList.Status> statuses = Boolean.TRUE.equals(done) ? List.of(PickList.Status.DONE)
                : List.of(PickList.Status.OPEN, PickList.Status.IN_PROGRESS);
        return PageResponse.of(picks.list(CurrentUser.require(), statuses, PageResponse.request(page, size, null, Map.of(),
                Sort.by(Boolean.TRUE.equals(done) ? Sort.Direction.DESC : Sort.Direction.ASC, "id"))), s -> s);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('picking:execute', 'outbound:read')")
    public Detail get(@PathVariable @Size(max = 26) String id) {
        return picks.get(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/lines/{seq}/confirm")
    @PreAuthorize("hasAuthority('picking:execute')")
    public Detail confirm(@PathVariable @Size(max = 26) String id, @PathVariable int seq,
            @RequestHeader(value = "Idempotency-Key", required = false) @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String key,
            @Valid @RequestBody ConfirmRequest body) {
        return picks.confirm(CurrentUser.require(), id, seq, body.binCode(), body.code(), body.qty(), key);
    }

    @PostMapping("/{id}/lines/{seq}/short")
    @PreAuthorize("hasAuthority('picking:execute')")
    public Detail shortPick(@PathVariable @Size(max = 26) String id, @PathVariable int seq, @Valid @RequestBody ShortRequest body) {
        return picks.shortPick(CurrentUser.require(), id, seq, body.reason());
    }
}
