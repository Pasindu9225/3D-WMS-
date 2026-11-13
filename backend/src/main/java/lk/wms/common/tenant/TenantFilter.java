package lk.wms.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lk.wms.common.security.CurrentUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-24: sets the tenant for the rest of the request from the verified session, never from
 * anything the client sends. Added to the security chain right after authentication.
 * Not a @Component, so Spring Boot does not also register it as a plain servlet filter.
 */
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = CurrentUser.get().map(CurrentUser::tenantId).orElse(null);
        Long previous = TenantContext.swap(tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.swap(previous);
        }
    }
}
