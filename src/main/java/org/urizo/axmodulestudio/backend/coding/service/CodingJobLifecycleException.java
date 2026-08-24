package org.urizo.axmodulestudio.backend.coding.service;

import java.util.Objects;

import org.springframework.http.HttpStatus;

public final class CodingJobLifecycleException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long retryAfterMs;

    public CodingJobLifecycleException(String code, String safeMessage, HttpStatus status) {
        this(code, safeMessage, status, false, null);
    }

    public CodingJobLifecycleException(
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
