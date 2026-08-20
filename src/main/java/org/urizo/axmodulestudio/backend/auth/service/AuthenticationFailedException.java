package org.urizo.axmodulestudio.backend.auth.service;

public final class AuthenticationFailedException extends RuntimeException {
    public static final String CODE = "AUTHENTICATION_REQUIRED";

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
