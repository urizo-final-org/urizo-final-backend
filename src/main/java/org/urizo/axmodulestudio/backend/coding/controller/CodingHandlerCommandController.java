package org.urizo.axmodulestudio.backend.coding.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingHandlerCommandService;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestController
@Validated
@Profile("dev & local-full & !dev-session & coding-job-local-fixture")
@RequestMapping("/api/coding-jobs")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public final class CodingHandlerCommandController {

    private final CodingHandlerCommandService service;
    private final AuthService authService;

    public CodingHandlerCommandController(
            CodingHandlerCommandService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @PostMapping
    ResponseEntity<CodingHandlerContract.CreateCodingJobResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CodingHandlerContract.CreateCodingJobRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        CodingHandlerContract.CreateCodingJobResponse response = service.create(
                actor(authentication), traceId(request), idempotencyKey, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/coding-jobs/" + response.job().jobId())
                .body(response);
    }

    @PutMapping("/{jobId}/request")
    CodingHandlerContract.JobRequestResponse initialize(
            @PathVariable UUID jobId,
            @Valid @RequestBody CodingHandlerContract.InitializeRequest body,
            Authentication authentication) {
        return service.initialize(actor(authentication), jobId, body);
    }

    @PostMapping("/{jobId}/approvals")
    CodingHandlerContract.ApprovalDecisionResponse decide(
            @PathVariable UUID jobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CodingHandlerContract.ApprovalDecisionRequest body,
            Authentication authentication) {
        return service.decide(actor(authentication), jobId, idempotencyKey, body);
    }

    @ExceptionHandler(CodingJobLifecycleException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> commandFailure(
            CodingJobLifecycleException failure,
            HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request,
                failure.code(),
                failure.getMessage(),
                failure.retryable(),
                failure.retryAfterMs()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> validationFailure(
            Exception failure,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request,
                "CONTRACT_VALIDATION_FAILED",
                "The Coding Handler command does not satisfy the contract.",
                false,
                null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> databaseFailure(
            DataAccessException failure,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request,
                "CODING_HANDLER_STORE_UNAVAILABLE",
                "The Coding Handler command store is unavailable.",
                true,
                1_000L));
    }

    private AuthenticatedActor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
        try {
            return authService.loadActor(UUID.fromString(authentication.getName()));
        }
        catch (IllegalArgumentException failure) {
            throw new AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
    }

    private static CodingJobLifecycleContract.ErrorEnvelope error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        String[] path = request.getRequestURI().split("/");
        UUID jobId = path.length > 3 ? parse(path[3]) : null;
        return new CodingJobLifecycleContract.ErrorEnvelope(
                CodingJobLifecycleContract.SCHEMA_VERSION,
                traceId(request),
                jobId,
                new CodingJobLifecycleContract.ErrorDetail(
                        code, message, retryable, retryAfterMs));
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static UUID traceId(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}
