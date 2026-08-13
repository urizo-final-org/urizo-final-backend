package org.urizo.axmodulestudio.backend.common.auth;

/** Raised when an administrator account id does not exist. */
public class AdminAccountNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "RESOURCE_NOT_FOUND";

    public AdminAccountNotFoundException(String message) {
        super(message);
    }
}
