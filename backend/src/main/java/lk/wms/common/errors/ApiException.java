package lk.wms.common.errors;

import org.springframework.http.HttpStatus;

/**
 * Expected business error. The message is shown to the client, so it must never contain
 * internal details. Example: new ApiException(HttpStatus.CONFLICT, "BIN_FULL", "Bin is full").
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object issues;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** {@code issues}: a list the client needs to fix the request (e.g. layout errors), sent as "issues". */
    public ApiException(HttpStatus status, String code, String message, Object issues) {
        super(message);
        this.status = status;
        this.code = code;
        this.issues = issues;
    }

    public Object getIssues() {
        return issues;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
