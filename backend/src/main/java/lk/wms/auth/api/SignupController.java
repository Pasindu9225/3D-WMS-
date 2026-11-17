package lk.wms.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lk.wms.auth.service.AuthService;
import lk.wms.auth.service.AuthService.Me;
import lk.wms.auth.service.SignupService;
import lk.wms.common.web.RequestInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public self-service sign-up (v2.0). 5 sign-ups per IP per hour (IpRateLimitFilter); CSRF like every POST. */
@RestController
@RequestMapping("/api/v1/signup")
public class SignupController {

    public record SignupRequest(@NotBlank @Size(max = 150) @Pattern(regexp = "[^\\p{Cntrl}]*") String companyName,
            @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*") String fullName,
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(min = 10, max = 128) String password) {
    }

    public record VerifyRequest(@NotBlank @Size(max = 100) String token) {
    }

    private final SignupService signups;

    public SignupController(SignupService signups) {
        this.signups = signups;
    }

    /** Always 202 "check your email", whether or not the email already has an account. */
    @PostMapping
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
        signups.request(body.companyName(), body.fullName(), body.email(), body.password(), RequestInfo.ip(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** The emailed link: makes the company and signs its admin in. */
    @PostMapping("/verify")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Me> verify(@Valid @RequestBody VerifyRequest body, HttpServletRequest request) {
        AuthService.LoginResult r = signups.verify(body.token(), RequestInfo.ip(request), RequestInfo.userAgent(request));
        return ResponseEntity.ok().headers(AuthCookies.issue(r.accessToken(), r.refreshToken())).body(r.me());
    }
}
