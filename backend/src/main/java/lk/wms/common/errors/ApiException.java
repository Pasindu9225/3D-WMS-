package lk.wms.common.errors;

import org.springframework.http.HttpStatus;

/**
 * Expected business error. The message is shown to the client, so it must never contain
 * internal details. Example: new ApiException(HttpStatus.CONFLICT, "BIN_FULL", "Bin is full").
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
