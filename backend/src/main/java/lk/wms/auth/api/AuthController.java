package lk.wms.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.wms.auth.service.AuthService;
import lk.wms.auth.service.AuthService.LoginResult;
import lk.wms.auth.service.AuthService.Me;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.RequestInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(
            @NotBlank @Size(max = 190) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** SEC-11: the app calls this once on start so the XSRF-TOKEN cookie exists before the first POST. */
    @GetMapping("/csrf")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken(); // loading the deferred token writes the cookie
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Me> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        LoginResult r = auth.login(body.email(), body.password(), RequestInfo.ip(request), RequestInfo.userAgent(request));
        return ResponseEntity.ok().headers(AuthCookies.issue(r.accessToken(), r.refreshToken())).body(r.me());
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Me> refresh(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            LoginResult r = auth.refresh(refreshToken);
            return ResponseEntity.ok().headers(AuthCookies.issue(r.accessToken(), r.refreshToken())).body(r.me());
        } catch (ApiException e) {
            addAll(response, AuthCookies.clear()); // dead token: make the browser drop it
            throw e;
        }
    }

    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> logout(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
            @CookieValue(name = AuthCookies.ACCESS, required = false) String accessToken) {
        auth.logout(refreshToken, accessToken);
        return ResponseEntity.noContent().headers(AuthCookies.clear()).build();
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logoutAll() {
        auth.logoutAll(CurrentUser.require());
        return ResponseEntity.noContent().headers(AuthCookies.clear()).build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Me me() {
        return auth.me(CurrentUser.require());
    }

    @PostMapping("/password/change")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        auth.changePassword(CurrentUser.require(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private static void addAll(HttpServletResponse response, HttpHeaders headers) {
        headers.forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
    }
}
