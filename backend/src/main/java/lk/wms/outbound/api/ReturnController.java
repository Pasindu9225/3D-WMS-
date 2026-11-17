package lk.wms.outbound.api;

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
import java.util.Locale;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.outbound.domain.ReturnLine;
import lk.wms.outbound.domain.ReturnOrder;
import lk.wms.outbound.service.ReturnService;
import lk.wms.outbound.service.ReturnService.Detail;
import lk.wms.outbound.service.ReturnService.Returnable;
import lk.wms.outbound.service.ReturnService.Summary;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer returns (v1.1 task 5). Create and cancel: outbound:manage. Inspect: inbound:receive (the dock). */
@RestController
public class ReturnController {

    public record LineRequest(@NotBlank @Size(max = 40) String sku, @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{0,9}") String uom,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @NotNull ReturnLine.Reason reason) {
    }

    public record CreateRequest(@NotBlank @Size(max = 26) String orderId, @Size(max = 500) String note,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull LineRequest> lines) {
    }

    public record InspectRequest(@NotNull ReturnLine.Outcome outcome, @Size(max = 60) String binCode,
            @Pattern(regexp = "[0-9A-Za-z._/-]{1,40}") String batchNo, LocalDate expiryDate) {
    }

    public record CancelRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 200) String reason) {
    }

    private final ReturnService returns;

    public ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    /** What can still come back from a shipped order (the New return form). */
    @GetMapping("/api/v1/sales-orders/{id}/returnable")
    @PreAuthorize("hasAuthority('outbound:manage')")
    public List<Returnable> returnable(@PathVariable @Size(max = 26) String id) {
        return returns.returnable(CurrentUser.require(), id);
    }

    /** Open returns (to inspect) by default; done=true for closed and cancelled ones. */
    @GetMapping("/api/v1/returns")
    @PreAuthorize("hasAnyAuthority('outbound:read', 'inbound:read')")
    public PageResponse<Summary> list(@RequestParam(required = false) Boolean done, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        List<ReturnOrder.Status> statuses = Boolean.TRUE.equals(done) ? List.of(ReturnOrder.Status.CLOSED, ReturnOrder.Status.CANCELLED)
                : List.of(ReturnOrder.Status.OPEN);
        return PageResponse.of(returns.list(CurrentUser.require(), statuses, PageResponse.request(page, size, null, Map.of(),
                Sort.by(Boolean.TRUE.equals(done) ? Sort.Direction.DESC : Sort.Direction.ASC, "id"))), s -> s);
    }

    @PostMapping("/api/v1/returns")
    @PreAuthorize("hasAuthority('outbound:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@Valid @RequestBody CreateRequest body) {
        return returns.create(CurrentUser.require(), body.orderId(), body.note(), body.lines().stream()
                .map(l -> new ReturnService.LineSpec(l.sku().trim().toUpperCase(Locale.ROOT), l.uom(), l.qty(), l.reason())).toList());
    }

    @GetMapping("/api/v1/returns/{id}")
    @PreAuthorize("hasAnyAuthority('outbound:read', 'inbound:read')")
    public Detail get(@PathVariable @Size(max = 26) String id) {
        return returns.get(CurrentUser.require(), id);
    }

    @PostMapping("/api/v1/returns/{id}/lines/{seq}/inspect")
    @PreAuthorize("hasAuthority('inbound:receive')")
    public Detail inspect(@PathVariable @Size(max = 26) String id, @PathVariable int seq, @Valid @RequestBody InspectRequest body) {
        return returns.inspect(CurrentUser.require(), id, seq, body.outcome(), body.binCode(), body.batchNo(), body.expiryDate());
    }

    @PostMapping("/api/v1/returns/{id}/cancel")
    @PreAuthorize("hasAuthority('outbound:manage')")
    public Detail cancel(@PathVariable @Size(max = 26) String id, @Valid @RequestBody CancelRequest body) {
        return returns.cancel(CurrentUser.require(), id, body.version(), body.reason());
    }
}
