package lk.wms.inbound.api;

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
import java.util.List;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.ReceiptService;
import lk.wms.inbound.service.ReceiptService.ReceiptDetail;
import lk.wms.inbound.service.ReceiptService.ReceiptSummary;
import lk.wms.inbound.service.ReceiptService.ScanSpec;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Receiving / GRN (v0.4 task 4). Read: inbound:read; open, scan, close: inbound:receive. */
@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    public record OpenRequest(@NotBlank @Size(max = 26) String poId) {
    }

    public record ScanRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9A-Za-z._-]+") String code, // barcode or SKU
            @Pattern(regexp = "[A-Z][A-Z0-9]{0,9}") String uom, // optional: the unit counted; default = the scanned one
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal damagedQty,
            @Size(max = 200) String damageNote) {
    }

    public record CloseRequest(@NotNull @Min(0) Integer version, @Size(max = 500) String note) {
    }

    private final ReceiptService receipts;

    public ReceiptController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    /** Receipts of one order (an order has a handful of deliveries; capped at 50). */
    @GetMapping
    @PreAuthorize("hasAuthority('inbound:read')")
    public List<ReceiptSummary> list(@RequestParam("po") @Size(max = 26) String poId) {
        return receipts.forOrder(CurrentUser.require(), poId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inbound:read')")
    public ReceiptDetail get(@PathVariable @Size(max = 26) String id) {
        return receipts.get(CurrentUser.require(), id);
    }

    /** Opens a receipt for the order, or returns the one already open. */
    @PostMapping
    @PreAuthorize("hasAuthority('inbound:receive')")
    public ReceiptDetail open(@Valid @RequestBody OpenRequest body) {
        return receipts.open(CurrentUser.require(), body.poId());
    }

    /** One scan. The phone sends a fresh Idempotency-Key per scan and repeats it on a retry. */
    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('inbound:receive')")
    public ReceiptDetail scan(@PathVariable @Size(max = 26) String id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[0-9A-Za-z-]{8,64}") String key,
            @Valid @RequestBody ScanRequest body) {
        return receipts.scan(CurrentUser.require(), id,
                new ScanSpec(body.code(), body.uom(), body.qty(), body.damagedQty(), body.damageNote(), key));
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAuthority('inbound:receive')")
    public ReceiptDetail removeScan(@PathVariable @Size(max = 26) String id, @PathVariable @Size(max = 26) String lineId) {
        return receipts.removeScan(CurrentUser.require(), id, lineId);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('inbound:receive')")
    public ReceiptDetail close(@PathVariable @Size(max = 26) String id, @Valid @RequestBody CloseRequest body) {
        return receipts.close(CurrentUser.require(), id, body.version(), body.note());
    }
}
