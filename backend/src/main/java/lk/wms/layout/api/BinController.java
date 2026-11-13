package lk.wms.layout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.BinService.BinView;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BinController {

    /** Each field optional; null leaves it as it is. Weight in grams (max 100 t), volume in cm³ (max 100 m³). */
    public record EditRequest(
            @Min(1) @Max(100_000_000) Long maxWeightG,
            @Min(1) @Max(100_000_000) Long maxVolumeCm3,
            Boolean pickFace,
            Boolean blocked) {
    }

    private final BinService bins;

    public BinController(BinService bins) {
        this.bins = bins;
    }

    @GetMapping("/api/v1/warehouses/{id}/bins")
    @PreAuthorize("hasAuthority('warehouses:read')")
    public PageResponse<BinView> list(@PathVariable @Size(max = 26) String id,
            @RequestParam(required = false) @Size(max = 40) String code,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(bins.list(CurrentUser.require(), id, code,
                PageResponse.request(page, size, null, Map.of(), Sort.by("code"))), b -> b);
    }

    @PatchMapping("/api/v1/bins/{id}")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public BinView edit(@PathVariable @Size(max = 26) String id, @Valid @RequestBody EditRequest body) {
        return bins.edit(CurrentUser.require(), id, body.maxWeightG(), body.maxVolumeCm3(), body.pickFace(), body.blocked());
    }
}
