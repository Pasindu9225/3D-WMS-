package lk.wms.report.api;

import lk.wms.common.security.CurrentUser;
import lk.wms.report.service.SetupService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Setup wizard steps for a new company (v2.0); for whoever sets the company up (company admins hold warehouses:manage). */
@RestController
public class SetupController {

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/api/v1/setup")
    @PreAuthorize("hasAuthority('warehouses:manage')")
    public SetupService.Status status() {
        return setup.status(CurrentUser.require());
    }
}
