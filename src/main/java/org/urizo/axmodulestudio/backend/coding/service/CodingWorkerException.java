package org.urizo.axmodulestudio.backend.coding.service;

import org.springframework.http.HttpStatus;

public final class CodingWorkerException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long retryAfterMs;

    CodingWorkerException(String code, String message, HttpStatus status) {
        this(code, message, status, false, null);
    }

    CodingWorkerException(
            String code, String message, HttpStatus status,
            boolean retryable, Long retryAfterMs) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public boolean retryable() { return retryable; }
    public Long retryAfterMs() { return retryAfterMs; }
}
