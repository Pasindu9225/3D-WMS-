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
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.inventory.domain.StockAdjustment;
import lk.wms.inventory.service.AdjustmentService;
import lk.wms.inventory.service.AdjustmentService.AdjustmentView;
import lk.wms.inventory.service.TransferService;
import lk.wms.inventory.service.TransferService.Moved;
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

/**
 * Transfers (inventory:transfer) and adjustments (inventory:adjust to ask, inventory:approve to decide,
 * inventory:read to see) of v0.4 task 7. Both are sent from phones with an Idempotency-Key.
 */
@RestController
@RequestMapping("/api/v1")
public class StockChangeController {

    static final String BIN = "[0-9A-Za-z._-]{1,40}";

    public record TransferRequest(
            @NotBlank @Size(max = 26) String warehouseId,
            @NotBlank @Size(max = 26) String productId,
            @Size(max = 40) String batchNo,
            LocalDate expiryDate,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @Pattern(regexp = BIN) String fromBin, // empty = the receiving area
            @NotBlank @Pattern(regexp = BIN) String toBin) {
    }

    public record AdjustmentRequest(
            @NotBlank @Size(max = 26) String warehouseId,
            @Pattern(regexp = BIN) String binCode, // empty = the receiving area
            @NotBlank @Size(max = 26) String productId,
            @Size(max = 40) String batchNo,
            LocalDate expiryDate,
            @NotNull StockAdjustment.Direction direction,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @NotNull StockAdjustment.Reason reason,
            @Size(max = 200) String note) {
    }

    public record DecisionRequest(@NotNull @Min(0) Integer version, @Size(max = 200) String note) {
    }

    public record RejectRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 200) String note) {
    }

    static final String KEY = "[0-9A-Za-z-]{8,64}";

    private final TransferService transfers;
    private final AdjustmentService adjustments;

    public StockChangeController(TransferService transfers, AdjustmentService adjustments) {
        this.transfers = transfers;
        this.adjustments = adjustments;
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('inventory:transfer')")
    public Moved transfer(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody TransferRequest b) {
        return transfers.transfer(CurrentUser.require(), b.warehouseId(), b.productId(), b.batchNo(), b.expiryDate(), b.qty(),
                b.fromBin(), b.toBin(), key);
    }

    @GetMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<AdjustmentView> list(@RequestParam(required = false) StockAdjustment.Status status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        List<StockAdjustment.Status> statuses = status == null ? List.of(StockAdjustment.Status.values()) : List.of(status);
        return PageResponse.of(adjustments.list(CurrentUser.require(), statuses,
                PageResponse.request(page, size, null, java.util.Map.of(), Sort.unsorted())), a -> a);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    public AdjustmentView request(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody AdjustmentRequest b) {
        return adjustments.request(CurrentUser.require(), new AdjustmentService.Request(b.warehouseId(), b.binCode(), b.productId(),
                b.batchNo(), b.expiryDate(), b.direction(), b.qty(), b.reason(), b.note(), key));
    }

    @PostMapping("/adjustments/{id}/approve")
    @PreAuthorize("hasAuthority('inventory:approve')")
    public AdjustmentView approve(@PathVariable @Size(max = 26) String id, @Valid @RequestBody DecisionRequest b) {
        return adjustments.approve(CurrentUser.require(), id, b.version(), b.note());
    }

    @PostMapping("/adjustments/{id}/reject")
    @PreAuthorize("hasAuthority('inventory:approve')")
    public AdjustmentView reject(@PathVariable @Size(max = 26) String id, @Valid @RequestBody RejectRequest b) {
        return adjustments.reject(CurrentUser.require(), id, b.version(), b.note());
    }
}
