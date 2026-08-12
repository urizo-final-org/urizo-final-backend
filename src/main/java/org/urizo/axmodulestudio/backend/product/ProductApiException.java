package org.urizo.axmodulestudio.backend.product;

import org.springframework.http.HttpStatus;

final class ProductApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long retryAfterMs;

    ProductApiException(String code, String message, HttpStatus status) {
        this(code, message, status, false, null);
    }

    ProductApiException(
            String code,
            String message,
            HttpStatus status,
            boolean retryable,
            Long retryAfterMs) {
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
