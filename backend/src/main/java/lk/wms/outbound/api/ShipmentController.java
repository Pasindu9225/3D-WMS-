package lk.wms.outbound.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.outbound.service.ShipmentService;
import lk.wms.outbound.service.ShipmentService.View;
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

/** Shipments (v1.0 task 5). Dispatch: dispatch:execute; read: dispatch:execute or outbound:read. */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {

    public record ShipmentRequest(
            @NotBlank @Size(max = 26) String warehouseId,
            @NotNull @Size(min = 1, max = 50) List<@NotBlank @Size(max = 26) String> orderIds,
            @Size(max = 20) String vehicleNo,
            @Size(max = 120) String driverName,
            @Size(max = 500) String note) {
    }

    private final ShipmentService shipments;

    public ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('dispatch:execute', 'outbound:read')")
    public PageResponse<View> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(shipments.list(CurrentUser.require(), PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.DESC, "id"))), v -> v);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('dispatch:execute', 'outbound:read')")
    public View get(@PathVariable @Size(max = 26) String id) {
        return shipments.get(CurrentUser.require(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch:execute')")
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody ShipmentRequest body) {
        return shipments.create(CurrentUser.require(), body.warehouseId(), body.orderIds(), body.vehicleNo(), body.driverName(), body.note());
    }
}
