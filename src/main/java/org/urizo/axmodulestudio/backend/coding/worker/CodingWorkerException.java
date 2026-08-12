package org.urizo.axmodulestudio.backend.coding.worker;

import org.springframework.http.HttpStatus;

final class CodingWorkerException extends RuntimeException {

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

    String code() { return code; }
    HttpStatus status() { return status; }
    boolean retryable() { return retryable; }
    Long retryAfterMs() { return retryAfterMs; }
}
