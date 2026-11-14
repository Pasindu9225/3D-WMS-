package lk.wms.inbound.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
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
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.inbound.domain.PurchaseOrder;
import lk.wms.inbound.service.PurchaseOrderService;
import lk.wms.inbound.service.PurchaseOrderService.Detail;
import lk.wms.inbound.service.PurchaseOrderService.LineSpec;
import lk.wms.inbound.service.PurchaseOrderService.OrderSpec;
import lk.wms.inbound.service.PurchaseOrderService.Summary;
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

/** Purchase orders (v0.4 task 3). Read: inbound:read; create, edit, approve, cancel: inbound:manage. */
@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {

    public record LineRequest(
            @NotBlank @Size(max = 40) String sku,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{0,9}") String uom,
            @NotNull @DecimalMin(value = "0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @Min(0) @Max(10_000_000_000L) Long unitCostMinor) { // optional: the product's cost x unit factor
    }

    public record OrderRequest(
            @NotBlank @Size(max = 20) String supplierCode,
            @NotBlank @Size(max = 26) String warehouseId,
            LocalDate expectedDate,
            @Size(max = 500) String note,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull LineRequest> lines,
            @Min(0) Integer version) { // edits only

        OrderSpec spec() {
            if (expectedDate != null && (expectedDate.isBefore(LocalDate.now().minusYears(1))
                    || expectedDate.isAfter(LocalDate.now().plusYears(2)))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_DATE", "Pick a date within the next two years");
            }
            return new OrderSpec(supplierCode.trim().toUpperCase(Locale.ROOT), warehouseId, expectedDate, note,
                    lines.stream().map(l -> new LineSpec(l.sku().trim().toUpperCase(Locale.ROOT), l.uom(), l.qty(),
                            l.unitCostMinor())).toList());
        }
    }

    public record VersionRequest(@NotNull @Min(0) Integer version) {
    }

    public record CancelRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 200) String reason) {
    }

    private final PurchaseOrderService orders;

    public PurchaseOrderController(PurchaseOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inbound:read')")
    public PageResponse<Summary> list(@RequestParam(required = false) PurchaseOrder.Status status,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @Size(max = 40) String sort) {
        return PageResponse.of(orders.list(CurrentUser.require(), status, q, PageResponse.request(page, size, sort,
                Map.of("number", "number", "expectedDate", "expectedDate", "total", "totalMinor"),
                Sort.by(Sort.Direction.DESC, "id"))), s -> s);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inbound:read')")
    public Detail get(@PathVariable @Size(max = 26) String id) {
        return orders.get(CurrentUser.require(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('inbound:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@Valid @RequestBody OrderRequest body) {
        return orders.create(CurrentUser.require(), body.spec());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inbound:manage')")
    public Detail update(@PathVariable @Size(max = 26) String id, @Valid @RequestBody OrderRequest body) {
        if (body.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "Send the version you edited");
        }
        return orders.update(CurrentUser.require(), id, body.version(), body.spec());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('inbound:manage')")
    public Detail approve(@PathVariable @Size(max = 26) String id, @Valid @RequestBody VersionRequest body) {
        return orders.approve(CurrentUser.require(), id, body.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('inbound:manage')")
    public Detail cancel(@PathVariable @Size(max = 26) String id, @Valid @RequestBody CancelRequest body) {
        return orders.cancel(CurrentUser.require(), id, body.version(), body.reason());
    }
}
