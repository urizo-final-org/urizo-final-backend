package org.urizo.axmodulestudio.backend.common.auth;

/** Raised when an authenticated actor lacks permission on a Project it can already see. */
public class AccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "FORBIDDEN";

    public AccessDeniedException(String message) {
        super(message);
    }
}
