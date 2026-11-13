package lk.wms.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lk.wms.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** SEC-24: the request runs in the session's tenant and the thread is clean afterwards. */
class TenantFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestRunsInTheAuthenticatedUsersTenant() throws Exception {
        CurrentUser user = new CurrentUser(1, "U", 42, "S", List.of(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        AtomicLong seen = new AtomicLong();

        new TenantFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(TenantContext.current()));

        assertThat(seen.get()).isEqualTo(42);
        assertThat(TenantContext.current()).isEqualTo(TenantContext.NONE); // nothing leaks to the next request
    }

    @Test
    void anonymousRequestHasNoTenant() throws Exception {
        AtomicLong seen = new AtomicLong();
        new TenantFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(TenantContext.current()));
        assertThat(seen.get()).isEqualTo(TenantContext.NONE);
    }
}
