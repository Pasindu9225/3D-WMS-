package wmstest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.wms.common.errors.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that trigger each error path. Outside lk.wms so only tests that @Import it get it. */
@RestController
@RequestMapping("/test")
public class ErrorProbeController {

    public record Body(@NotBlank @Size(max = 20) String name) {
    }

    @GetMapping("/boom")
    @PreAuthorize("isAuthenticated()")
    public String boom() {
        throw new IllegalStateException("SECRET db password=hunter2 at com.mysql.Driver");
    }

    @GetMapping("/business")
    @PreAuthorize("isAuthenticated()")
    public String business() {
        throw new ApiException(HttpStatus.CONFLICT, "BIN_FULL", "Bin is full");
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasAuthority('users:manage')")
    public String adminOnly() {
        return "ok";
    }

    @PostMapping("/validate")
    @PreAuthorize("isAuthenticated()")
    public String validate(@Valid @RequestBody Body body) {
        return body.name();
    }
}
