package lk.wms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lk.wms.common.security.CurrentUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-60: per-user limit, added to the security chain after authentication.
 * Not a @Component, so Spring Boot does not also register it as a plain servlet filter.
 * ponytail: one limit for every endpoint; worker scan endpoints get 300/min when they exist (v0.4).
 */
public class UserRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public UserRateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<CurrentUser> user = CurrentUser.get();
        if (user.isPresent()) {
            RateLimiter.Scope scope = isScan(request) ? RateLimiter.Scope.SCAN : RateLimiter.Scope.USER;
            long wait = limiter.tryConsume(scope, Long.toString(user.get().userId()));
            if (wait > 0) {
                IpRateLimitFilter.tooMany(response, wait);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Phone scan endpoints get their own, larger bucket (300/min) so a fast worker is not cut off. */
    static boolean isScan(HttpServletRequest request) {
        String p = request.getRequestURI();
        return ("POST".equals(request.getMethod()) && (p.matches("/api/v1/receipts/[^/]+/lines") || p.equals("/api/v1/putaway")
                || p.equals("/api/v1/transfers") || p.matches("/api/v1/pick-lists/[^/]+/lines/\\d+/confirm"))) || p.startsWith("/api/v1/products/by-barcode/");
    }
}
