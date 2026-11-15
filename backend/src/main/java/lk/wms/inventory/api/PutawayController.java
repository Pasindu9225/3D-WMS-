package lk.wms.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.inventory.service.PutawayService;
import lk.wms.inventory.service.PutawayService.Done;
import lk.wms.inventory.service.PutawayService.Suggestion;
import lk.wms.inventory.service.PutawayService.Waiting;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Putaway (v0.4 task 5). Everything needs putaway:execute. */
@RestController
@RequestMapping("/api/v1/putaway")
public class PutawayController {

    public record SuggestRequest(
            @NotBlank @Size(max = 26) String warehouseId,
            @NotBlank @Size(max = 26) String productId,
            @Size(max = 40) String batchNo,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty) {
    }

    public record ConfirmRequest(
            @NotBlank @Size(max = 26) String warehouseId,
            @NotBlank @Size(max = 26) String productId,
            @Size(max = 40) String batchNo,
            LocalDate expiryDate,
            @NotNull @DecimalMin("0.001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal qty,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[0-9A-Za-z._-]+") String binCode) { // as scanned from the label
    }

    private final PutawayService putaway;

    public PutawayController(PutawayService putaway) {
        this.putaway = putaway;
    }

    /** Goods in the receiving area of a warehouse, oldest first. */
    @GetMapping
    @PreAuthorize("hasAuthority('putaway:execute')")
    public PageResponse<Waiting> waiting(@RequestParam("warehouse") @Size(max = 26) String warehouseId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(putaway.waiting(CurrentUser.require(), warehouseId,
                PageResponse.request(page, size, null, java.util.Map.of(), Sort.unsorted())), w -> w);
    }

    /** Up to three bins for this product and quantity. */
    @PostMapping("/suggestions")
    @PreAuthorize("hasAuthority('putaway:execute')")
    public List<Suggestion> suggest(@Valid @RequestBody SuggestRequest body) {
        return putaway.suggest(CurrentUser.require(), body.warehouseId(), body.productId(), body.batchNo(), body.qty());
    }

    /** Puts the goods into the bin whose label was scanned. The phone sends an Idempotency-Key per putaway. */
    @PostMapping
    @PreAuthorize("hasAuthority('putaway:execute')")
    public Done confirm(@RequestHeader("Idempotency-Key") @Pattern(regexp = "[0-9A-Za-z-]{8,64}") String key,
            @Valid @RequestBody ConfirmRequest body) {
        return putaway.confirm(CurrentUser.require(), body.warehouseId(), body.productId(), body.batchNo(), body.expiryDate(),
                body.qty(), body.binCode(), key);
    }
}
