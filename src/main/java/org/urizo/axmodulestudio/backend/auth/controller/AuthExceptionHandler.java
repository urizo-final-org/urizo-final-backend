package org.urizo.axmodulestudio.backend.auth.controller;

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
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorDetail;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorResponse;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestControllerAdvice(basePackageClasses = AuthController.class)
@Profile("local-full & !dev-session")
public class AuthExceptionHandler {

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> authenticationFailure(
            AuthenticationFailedException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(error(request, AuthenticationFailedException.CODE,
                        "A valid session is required."));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request, "VALIDATION_FAILED",
                "The request does not satisfy the public API contract."));
    }

    private static ErrorResponse error(HttpServletRequest request, String code, String message) {
        return new ErrorResponse(
                "1.0",
                UUID.fromString(String.valueOf(
                        request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE))),
                new ErrorDetail(code, message, false, null));
    }

}
