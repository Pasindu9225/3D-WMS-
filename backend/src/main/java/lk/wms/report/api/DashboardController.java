package lk.wms.report.api;

import jakarta.validation.constraints.Size;
import lk.wms.common.security.CurrentUser;
import lk.wms.report.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard (v1.0 task 7): reports:read. */
@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    @PreAuthorize("hasAuthority('reports:read')")
    public DashboardService.Dashboard get(@RequestParam("warehouse") @Size(max = 26) String warehouseId) {
        return dashboard.build(CurrentUser.require(), warehouseId);
    }
}
