package lk.wms.auth.api;

import java.time.Duration;
import lk.wms.auth.service.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * SEC-01: tokens live only in HttpOnly cookies, never in the response body or browser storage.
 * __Host- prefix: browser enforces Secure, Path=/ and no Domain (cannot be set by a subdomain).
 * The refresh cookie is only sent to /api/v1/auth, so a normal API call never carries it.
 */
public final class AuthCookies {

    public static final String ACCESS = "__Host-wms_at";
    public static final String REFRESH = "__Secure-wms_rt";
    static final String REFRESH_PATH = "/api/v1/auth";

    private AuthCookies() {
    }

    static HttpHeaders issue(String accessToken, String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, access(accessToken, TokenService.ACCESS_TTL).toString());
        headers.add(HttpHeaders.SET_COOKIE, refresh(refreshToken, TokenService.REFRESH_TTL).toString());
        return headers;
    }

    static HttpHeaders clear() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, access("", Duration.ZERO).toString());
        headers.add(HttpHeaders.SET_COOKIE, refresh("", Duration.ZERO).toString());
        return headers;
    }

    private static ResponseCookie access(String value, Duration maxAge) {
        return ResponseCookie.from(ACCESS, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAge).build();
    }

    private static ResponseCookie refresh(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH, value)
                .httpOnly(true).secure(true).sameSite("Strict").path(REFRESH_PATH).maxAge(maxAge).build();
    }
}
