package lk.wms.layout.api;

import jakarta.validation.constraints.Size;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.service.SceneService;
import lk.wms.layout.service.SceneService.SceneView;
import lk.wms.layout.service.SceneService.StatusView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Data for the 3D viewer (v0.3): the published layout and the live bin status. */
@RestController
@RequestMapping("/api/v1/warehouses/{id}")
public class SceneController {

    private final SceneService scenes;

    public SceneController(SceneService scenes) {
        this.scenes = scenes;
    }

    @GetMapping("/scene")
    @PreAuthorize("hasAuthority('warehouses:read')")
    public SceneView scene(@PathVariable @Size(max = 26) String id) {
        return scenes.scene(CurrentUser.require(), id);
    }

    @GetMapping("/bin-status")
    @PreAuthorize("hasAuthority('warehouses:read')")
    public StatusView status(@PathVariable @Size(max = 26) String id) {
        return scenes.status(CurrentUser.require(), id);
    }
}
