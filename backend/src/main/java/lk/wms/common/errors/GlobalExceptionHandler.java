package lk.wms.common.errors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lk.wms.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * SEC-63: every error is an RFC 9457 ProblemDetail with {@code code} and {@code correlationId}.
 * Clients never see stack traces or exception messages; the full error goes to the server log only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode());
        if (ex.getIssues() != null) {
            pd.setProperty("issues", ex.getIssues());
        }
        return createResponseEntity(pd, new HttpHeaders(), ex.getStatus(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException ex, WebRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required", request);
    }

    /** SEC-11: own code so the app knows to fetch a fresh token and retry once. */
    @ExceptionHandler(CsrfException.class)
    ResponseEntity<Object> handleCsrf(CsrfException ex, WebRequest request) {
        return problem(HttpStatus.FORBIDDEN, "CSRF_INVALID", "Security token missing or expired. Reload and try again.",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request);
    }

    /** Two people saved the same record at once (@Version); the later one reloads and tries again. */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleStale(Exception ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "STALE", "Someone else changed this just now; reload it", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", request);
    }

    /** Validation errors: 400 with a list of {field, message}. Rejected values are never echoed back. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(),
                        "message", Objects.requireNonNullElse(f.getDefaultMessage(), "invalid")))
                .toList());
        return createResponseEntity(body, headers, status, request);
    }

    /**
     * Same {field, message} list when Spring validates the method as a whole, which it does as soon as
     * any parameter (e.g. a path variable) carries a constraint. Without this, those endpoints answered
     * a bare "Validation failure" with no field list. Nested fields keep their path (layout.racks[0].code).
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors body) {
                body.getFieldErrors().forEach(f -> errors.add(Map.of("field", f.getField(),
                        "message", Objects.requireNonNullElse(f.getDefaultMessage(), "invalid"))));
            } else {
                String name = Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "parameter");
                result.getResolvableErrors().forEach(e -> errors.add(Map.of("field", name,
                        "message", Objects.requireNonNullElse(e.getDefaultMessage(), "invalid"))));
            }
        }
        ProblemDetail body = ex.getBody();
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return createResponseEntity(body, headers, status, request);
    }

    /** SEC-40: uploads over the multipart limit (5 MB images) get a clear code instead of a bare 413. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Files can be at most 5 MB", request);
    }

    /** Every ProblemDetail (ours and the built-in Spring ones) passes through here. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                HttpStatus resolved = HttpStatus.resolve(status.value());
                pd.setProperty("code", resolved != null ? resolved.name() : "ERROR");
            }
            pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> problem(HttpStatus status, String code, String detail, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return createResponseEntity(pd, new HttpHeaders(), status, request);
    }
}
