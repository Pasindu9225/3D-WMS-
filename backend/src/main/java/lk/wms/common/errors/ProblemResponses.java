package lk.wms.common.errors;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lk.wms.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * Writes a ProblemDetail from a servlet filter, which runs before Spring MVC and so cannot use
 * GlobalExceptionHandler. Same shape as every other error (SEC-63).
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    /** {@code code} and {@code detail} must be constants: they are written without JSON escaping. */
    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\",\"code\":\"" + code
                + "\",\"correlationId\":" + (cid == null ? "null" : "\"" + cid + "\"") + "}");
    }
}
