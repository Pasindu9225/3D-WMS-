package lk.wms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lk.wms.common.errors.ProblemResponses;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-60: per-IP limits, checked before Spring Security so floods cost almost nothing.
 * Login has its own tighter bucket (brute force); everything else shares the global one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IpRateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/v1/auth/login";

    private final RateLimiter limiter;

    public IpRateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = RequestInfo.clientKey(RequestInfo.ip(request)); // IPv6 counts per /64 (F7)
        long wait = limiter.tryConsume(RateLimiter.Scope.GLOBAL, ip);
        if (wait == 0 && "POST".equals(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI())) {
            wait = limiter.tryConsume(RateLimiter.Scope.LOGIN, ip);
        }
        if (wait > 0) {
            tooMany(response, wait);
            return;
        }
        chain.doFilter(request, response);
    }

    static void tooMany(HttpServletResponse response, long waitSeconds) throws IOException {
        response.setHeader("Retry-After", Long.toString(waitSeconds));
        ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many requests. Wait a moment and try again.");
    }
}
