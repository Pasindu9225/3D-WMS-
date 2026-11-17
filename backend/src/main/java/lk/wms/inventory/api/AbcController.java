package lk.wms.inventory.api;

import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.service.AbcService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** ABC classes (v1.1 task 4): worked out weekly; a manager can recalculate now. */
@RestController
public class AbcController {

    private final AbcService abc;

    public AbcController(AbcService abc) {
        this.abc = abc;
    }

    @PostMapping("/api/v1/abc/recalculate")
    @PreAuthorize("hasAuthority('counts:approve')")
    public AbcService.Result recalculate() {
        return abc.recalculate(CurrentUser.require());
    }
}
