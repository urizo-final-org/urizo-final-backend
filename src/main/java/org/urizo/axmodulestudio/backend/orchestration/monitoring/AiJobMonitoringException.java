package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import org.springframework.http.HttpStatus;

public final class AiJobMonitoringException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    AiJobMonitoringException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
