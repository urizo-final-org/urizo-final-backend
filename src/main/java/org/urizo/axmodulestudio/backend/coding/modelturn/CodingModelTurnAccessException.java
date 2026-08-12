package org.urizo.axmodulestudio.backend.coding.modelturn;

import java.util.Objects;

import org.springframework.http.HttpStatus;

public final class CodingModelTurnAccessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long retryAfterMs;

    public CodingModelTurnAccessException(String code, String safeMessage, HttpStatus status) {
        this(code, safeMessage, status, false, null);
    }

    public CodingModelTurnAccessException(
            String code,
            String safeMessage,
            HttpStatus status,
            boolean retryable,
            Long retryAfterMs) {
        super(safeMessage);
        this.code = Objects.requireNonNull(code, "code is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
        if (status != HttpStatus.BAD_REQUEST && status != HttpStatus.UNAUTHORIZED
                && status != HttpStatus.FORBIDDEN && status != HttpStatus.NOT_FOUND
                && status != HttpStatus.CONFLICT && status != HttpStatus.UNPROCESSABLE_ENTITY
                && status != HttpStatus.TOO_MANY_REQUESTS && status != HttpStatus.SERVICE_UNAVAILABLE
                && status != HttpStatus.GATEWAY_TIMEOUT) {
            throw new IllegalArgumentException("Unsupported Model Turn access status.");
        }
        if (retryable != (retryAfterMs != null) || (retryAfterMs != null && retryAfterMs < 1)) {
            throw new IllegalArgumentException("Retry metadata is inconsistent.");
        }
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public Long retryAfterMs() {
        return retryAfterMs;
    }
}
