package org.urizo.axmodulestudio.backend.common.auth;

/** Raised when an administrator login id is already taken. */
public class AdminAccountConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "LOGIN_ID_ALREADY_EXISTS";

    public AdminAccountConflictException(String message) {
        super(message);
    }
}
