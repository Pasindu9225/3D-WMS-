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
            long wait = limiter.tryConsume(RateLimiter.Scope.USER, Long.toString(user.get().userId()));
            if (wait > 0) {
                IpRateLimitFilter.tooMany(response, wait);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
