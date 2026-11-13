package lk.wms.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import lk.wms.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** SEC-11, 20, 21, 61, 62, 64, 65 through the real filter chain. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "wms.cors.allowed-origins=https://app.example.lk")
class SecurityHardeningTest extends IntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String BODY = "{\"email\":\"nobody@x.lk\",\"password\":\"whatever-pass\"}";

    @Autowired
    MockMvc mvc;

    // ---------------------------------------------------------------- CSRF (SEC-11)

    @Test
    void csrfEndpointIssuesAReadableStrictCookie() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        String setCookie = r.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("Secure", "Path=/").doesNotContain("HttpOnly");
        // SameSite is a cookie attribute; Tomcat writes it, MockMvc leaves it out of the header text
        assertThat(r.getResponse().getCookie("XSRF-TOKEN").getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    void postWithoutCsrfTokenIsRejectedWithItsOwnCode() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void postWithCookieAndMatchingHeaderPassesCsrf() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        String token = r.getResponse().getCookie("XSRF-TOKEN").getValue();
        mvc.perform(post(LOGIN).cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()) // got past CSRF to the (failing) login
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
        mvc.perform(post(LOGIN).cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", "forged")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- input (SEC-20, SEC-21)

    @Test
    void unknownJsonFieldIsRejected() throws Exception {
        mvc.perform(post(LOGIN).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.lk\",\"password\":\"p\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void overlongFieldIsRejected() throws Exception {
        mvc.perform(post(LOGIN).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + "a".repeat(300) + "\",\"password\":\"p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    // ---------------------------------------------------------------- request limits (SEC-65)

    @Test
    void bodyOverOneMegabyteIsRejectedBeforeParsing() throws Exception {
        byte[] big = new byte[1_048_577];
        java.util.Arrays.fill(big, (byte) 'a');
        mvc.perform(post(LOGIN).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    // ---------------------------------------------------------------- headers (SEC-61, SEC-64)

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("default-src 'self'"),
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsafe-inline")))))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.startsWith("camera=(self)")))
                .andExpect(header().doesNotExist("Server"))
                .andExpect(header().doesNotExist("X-Powered-By"));
    }

    @Test
    void healthIsOnlyReachableFromTheServerItself() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()); // MockMvc default is 127.0.0.1
        mvc.perform(get("/actuator/health").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        })).andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- CORS (SEC-62)

    @Test
    void corsAllowsOnlyListedOrigins() throws Exception {
        mvc.perform(options(LOGIN).header("Origin", "https://app.example.lk")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.lk"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options(LOGIN).header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void wildcardOriginRefusesToStart() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lk.wms.auth.config.SecurityConfig.cors(java.util.List.of("*")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Real double-submit token (cookie == header). Spring's csrf() test helper is not used in this class:
     * it swaps the token store in the shared context, so the cookie tests above would stop seeing cookies.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor xsrf() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "test-xsrf-token"));
            request.addHeader("X-XSRF-TOKEN", "test-xsrf-token");
            return request;
        };
    }
}
