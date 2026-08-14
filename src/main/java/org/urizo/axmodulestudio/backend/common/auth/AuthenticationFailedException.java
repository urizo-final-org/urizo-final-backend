package org.urizo.axmodulestudio.backend.common.auth;

/**
 * Raised whenever a credential or session check fails.
 *
 * <p>The message is deliberately identical for an unknown login id, a wrong password, and a disabled
 * account so a caller cannot enumerate administrator accounts.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "AUTHENTICATION_REQUIRED";

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
