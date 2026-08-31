package org.urizo.axmodulestudio.backend.cms.assistant;

import org.springframework.http.HttpStatus;

public final class NaturalCmsException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;

    public NaturalCmsException(String code, String message, HttpStatus status) {
        this(code, message, status, false);
    }

    public NaturalCmsException(
            String code, String message, HttpStatus status, boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
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
}
