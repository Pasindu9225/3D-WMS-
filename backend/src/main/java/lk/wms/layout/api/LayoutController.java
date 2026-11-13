package lk.wms.layout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.service.LayoutDocument;
import lk.wms.layout.service.LayoutPublisher;
import lk.wms.layout.service.LayoutPublisher.PublishResult;
import lk.wms.layout.service.LayoutService;
import lk.wms.layout.service.LayoutService.LayoutView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses/{id}/layout")
public class LayoutController {

    /** {@code baseRevision}: the revision the editor loaded (0 if none); stale saves get 409. */
    public record SaveRequest(@Min(0) int baseRevision, @NotNull @Valid LayoutDocument layout) {
    }

    private final LayoutService layouts;
    private final LayoutPublisher publisher;

    public LayoutController(LayoutService layouts, LayoutPublisher publisher) {
        this.layouts = layouts;
        this.publisher = publisher;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('warehouses:read')")
    public LayoutView get(@PathVariable @Size(max = 26) String id) {
        return layouts.get(CurrentUser.require(), id);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public LayoutView save(@PathVariable @Size(max = 26) String id, @Valid @RequestBody SaveRequest body) {
        return layouts.saveDraft(CurrentUser.require(), id, body.baseRevision(), body.layout());
    }

    /** Publish the draft the editor shows ({@code baseRevision}); the floor then works with the new layout. */
    public record PublishRequest(@Min(1) int baseRevision) {
    }

    @PostMapping("/publish")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public PublishResult publish(@PathVariable @Size(max = 26) String id, @Valid @RequestBody PublishRequest body) {
        return publisher.publish(CurrentUser.require(), id, body.baseRevision());
    }
}
