package org.urizo.axmodulestudio.backend.knowledge.controller;

import java.util.UUID;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

@RestControllerAdvice(basePackageClasses = ProductApiController.class)
@Profile("local-full")
final class ProductApiExceptionHandler {

    private static final Set<String> PUBLIC_CONFLICT_CODES = Set.of(
            "IDEMPOTENCY_KEY_REUSED",
            "IDEMPOTENCY_REQUEST_IN_PROGRESS",
            "JOB_STATE_CONFLICT",
            "KNOWLEDGE_VERSION_NOT_ACTIVE");
    private static final Set<String> PUBLIC_SERVICE_UNAVAILABLE_CODES = Set.of(
            "SERVICE_NOT_READY", "PROVIDER_UNAVAILABLE", "LLM_NOT_CONFIGURED");

    @ExceptionHandler(ProductApiException.class)
    ResponseEntity<ProductApiContract.ErrorEnvelope> productFailure(
            ProductApiException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request, publicCode(failure), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ProductApiContract.ErrorEnvelope> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request, "VALIDATION_FAILED",
                "The request does not satisfy the public API contract.", false, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProductApiContract.ErrorEnvelope> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request, "SERVICE_NOT_READY",
                "The authoritative product store is unavailable.", true, 1_000L));
    }

    private static ProductApiContract.ErrorEnvelope error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        return new ProductApiContract.ErrorEnvelope(
                ProductApiContract.SCHEMA_VERSION,
                UUID.fromString(String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE))),
                new ProductApiContract.ErrorDetail(code, message, retryable, retryAfterMs));
    }

    static String publicCode(ProductApiException failure) {
        return switch (failure.status()) {
            case BAD_REQUEST -> "VALIDATION_FAILED";
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case CONFLICT -> conflictCode(failure.code());
            case UNPROCESSABLE_ENTITY -> "CONNECTOR_RESPONSE_INVALID".equals(failure.code())
                    ? "CONNECTOR_RESPONSE_INVALID" : "CONNECTOR_SPEC_INVALID";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case BAD_GATEWAY -> "UPSTREAM_SERVICE_ERROR";
            case SERVICE_UNAVAILABLE -> PUBLIC_SERVICE_UNAVAILABLE_CODES.contains(failure.code())
                    ? failure.code() : "SERVICE_NOT_READY";
            case GATEWAY_TIMEOUT -> "UPSTREAM_TIMEOUT";
            default -> "INTERNAL_ERROR";
        };
    }

    private static String conflictCode(String code) {
        if ("ACTIVE_KNOWLEDGE_REQUIRED".equals(code)) {
            return "KNOWLEDGE_VERSION_NOT_ACTIVE";
        }
        return PUBLIC_CONFLICT_CODES.contains(code) ? code : "JOB_STATE_CONFLICT";
    }
}
