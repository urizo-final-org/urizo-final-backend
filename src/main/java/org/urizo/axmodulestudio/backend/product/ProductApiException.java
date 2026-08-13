package org.urizo.axmodulestudio.backend.product;

import org.springframework.http.HttpStatus;

public final class ProductApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long retryAfterMs;

    public ProductApiException(String code, String message, HttpStatus status) {
        this(code, message, status, false, null);
    }

    public ProductApiException(
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

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public boolean retryable() { return retryable; }
    public Long retryAfterMs() { return retryAfterMs; }
}
