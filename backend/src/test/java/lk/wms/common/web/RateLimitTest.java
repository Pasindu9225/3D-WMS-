package lk.wms.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import lk.wms.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** SEC-60 with small limits: global 5/min, login 2/min, user 3/min. */
class RateLimitTest {

    private final RateLimiter limiter = new RateLimiter(5, 2, 3);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginIsLimitedPerIpWithRetryAfter() throws Exception {
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter);
        assertThat(send(filter, "POST", IpRateLimitFilter.LOGIN_PATH, "10.1.1.1").getStatus()).isEqualTo(200);
        assertThat(send(filter, "POST", IpRateLimitFilter.LOGIN_PATH, "10.1.1.1").getStatus()).isEqualTo(200);
        MockHttpServletResponse third = send(filter, "POST", IpRateLimitFilter.LOGIN_PATH, "10.1.1.1");
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(third.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(third.getContentType()).startsWith("application/problem+json");
        assertThat(third.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");

        // other endpoints from the same IP and logins from another IP are unaffected
        assertThat(send(filter, "GET", "/api/v1/auth/me", "10.1.1.1").getStatus()).isEqualTo(200);
        assertThat(send(filter, "POST", IpRateLimitFilter.LOGIN_PATH, "10.1.1.2").getStatus()).isEqualTo(200);
    }

    @Test
    void globalLimitPerIp() throws Exception {
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, "GET", "/api/v1/anything", "10.2.2.2").getStatus()).isEqualTo(200);
        }
        assertThat(send(filter, "GET", "/api/v1/anything", "10.2.2.2").getStatus()).isEqualTo(429);
    }

    @Test
    void userLimitFollowsTheUserNotTheIp() throws Exception {
        UserRateLimitFilter filter = new UserRateLimitFilter(limiter);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(77, "U", 1, "S", List.of(), Set.of()), null, List.of()));
        for (int i = 0; i < 3; i++) {
            assertThat(send(filter, "GET", "/api/v1/x", "10.3.3." + i).getStatus()).isEqualTo(200);
        }
        assertThat(send(filter, "GET", "/api/v1/x", "10.3.3.99").getStatus()).isEqualTo(429);
    }

    @Test
    void anonymousRequestsSkipTheUserLimit() throws Exception {
        UserRateLimitFilter filter = new UserRateLimitFilter(limiter);
        for (int i = 0; i < 10; i++) {
            assertThat(send(filter, "GET", "/api/v1/x", "10.4.4.4").getStatus()).isEqualTo(200);
        }
    }

    private static MockHttpServletResponse send(jakarta.servlet.Filter filter, String method, String uri, String ip)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
