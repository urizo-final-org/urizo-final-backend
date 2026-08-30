package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.Objects;

import org.springframework.http.HttpStatus;

public final class ProfileVersionException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long retryAfterMs;

    public ProfileVersionException(String code, String message, HttpStatus status) {
        this(code, message, status, false, null);
    }

    public ProfileVersionException(
            String code,
            String message,
            HttpStatus status,
            boolean retryable,
            Long retryAfterMs) {
        super(message);
        this.code = Objects.requireNonNull(code, "code is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
        if (retryable != (retryAfterMs != null) || (retryAfterMs != null && retryAfterMs < 1)) {
            throw new IllegalArgumentException("Retry metadata is inconsistent.");
        }
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public boolean retryable() { return retryable; }
    public Long retryAfterMs() { return retryAfterMs; }
}
