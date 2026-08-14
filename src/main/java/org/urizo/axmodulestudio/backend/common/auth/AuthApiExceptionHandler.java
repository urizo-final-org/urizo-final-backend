package org.urizo.axmodulestudio.backend.common.auth;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

/**
 * Error envelopes for the authentication boundary.
 *
 * <p>The Stage 3 advice is scoped to its own package and therefore does not cover these controllers.
 *
 * <p>Only 401 and 400 are mapped here. An unknown login id, a wrong password, and a disabled account
 * all produce the same 401 body so a caller cannot enumerate administrator accounts.
 */
@RestControllerAdvice(basePackageClasses = AuthApiController.class)
@Profile("local-full")
final class AuthApiExceptionHandler {

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<AuthApiContract.ErrorEnvelope> authenticationFailure(
            AuthenticationFailedException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(error(request, AuthenticationFailedException.CODE,
                        "A valid administrator session is required."));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<AuthApiContract.ErrorEnvelope> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(request, "VALIDATION_FAILED",
                "The request does not satisfy the public API contract."));
    }

    private static AuthApiContract.ErrorEnvelope error(
            HttpServletRequest request, String code, String message) {
        return new AuthApiContract.ErrorEnvelope(
                AuthApiContract.SCHEMA_VERSION,
                UUID.fromString(String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE))),
                new AuthApiContract.ErrorDetail(code, message, false, null));
    }
}
