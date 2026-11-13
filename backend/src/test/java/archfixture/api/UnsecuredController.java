package archfixture.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately broken fixture for ArchitectureRulesSelfTest. Outside lk.wms so Spring never scans it. */
@RestController
public class UnsecuredController {

    @GetMapping("/fixture")
    public String open() {
        return "no @PreAuthorize";
    }
}
