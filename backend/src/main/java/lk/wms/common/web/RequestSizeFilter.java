package lk.wms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lk.wms.common.errors.ProblemResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-65: JSON bodies are capped (1 MB) before anything reads them. Bodies of unknown length
 * (chunked) are refused, since the cap could not be checked up front; browsers always send a length.
 * File uploads (multipart) are left to the multipart limits set per upload type (v0.4).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxJsonBytes;

    public RequestSizeFilter(@Value("${wms.request.max-json-bytes}") long maxJsonBytes) {
        this.maxJsonBytes = maxJsonBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        if (!multipart) {
            long length = request.getContentLengthLong();
            if (length > maxJsonBytes) {
                ProblemResponses.write(response, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                        "Request body is too large.");
                return;
            }
            String te = request.getHeader("Transfer-Encoding");
            if (length < 0 && te != null && te.toLowerCase().contains("chunked")) {
                ProblemResponses.write(response, HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED",
                        "Request body must have a Content-Length.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
