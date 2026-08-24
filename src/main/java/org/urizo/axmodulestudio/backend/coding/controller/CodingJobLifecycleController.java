package org.urizo.axmodulestudio.backend.coding.controller;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalDevRequestGuard;

@RestController
@Validated
@Profile("dev & coding-job-local-fixture")
@RequestMapping("/internal/dev/coding-jobs")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class CodingJobLifecycleController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final CodingJobLifecycleService service;
    private final LocalDevRequestGuard requestGuard;
    private final Clock clock;

    public CodingJobLifecycleController(
            CodingJobLifecycleService service,
            LocalDevRequestGuard requestGuard,
            Clock clock) {
        this.service = service;
        this.requestGuard = requestGuard;
        this.clock = clock;
    }

    @GetMapping("/session")
    CodingJobLifecycleContract.SessionResponse session(HttpServletRequest request) {
        return new CodingJobLifecycleContract.SessionResponse(
                CodingJobLifecycleContract.SCHEMA_VERSION,
                requestGuard.csrfToken(request),
                true,
                Instant.now(clock));
    }

    @PostMapping
    ResponseEntity<CodingJobLifecycleContract.JobResponse> create(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody CodingJobLifecycleContract.CreateRequest body,
            HttpServletRequest request) {
        requestGuard.requireMutation(request);
        CodingJobLifecycleContract.JobResponse response = service.create(
                traceId(request), idempotencyKey, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/internal/dev/coding-jobs/" + response.jobId())
                .body(response);
    }

    @GetMapping("/{jobId}")
    CodingJobLifecycleContract.JobResponse get(
            @PathVariable UUID jobId,
            HttpServletRequest request) {
        requestGuard.requireRead(request);
        return service.find(jobId, traceId(request));
    }

    @PostMapping("/{jobId}/transitions")
    CodingJobLifecycleContract.JobResponse transition(
            @PathVariable UUID jobId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody CodingJobLifecycleContract.TransitionRequest body,
            HttpServletRequest request) {
        requestGuard.requireMutation(request);
        return service.transition(jobId, traceId(request), idempotencyKey, body);
    }

    @ExceptionHandler(CodingJobLifecycleException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> lifecycleFailure(
            CodingJobLifecycleException failure,
            HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request,
                failure.code(),
                failure.getMessage(),
                failure.retryable(),
                failure.retryAfterMs()));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> accessFailure(
            SecurityException failure,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(
                request,
                "LOCAL_CODING_JOB_ACCESS_DENIED",
                "Local coding job fixture access was denied.",
                false,
                null));
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
                "The coding job lifecycle request does not satisfy the contract.",
                false,
                null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> databaseFailure(
            DataAccessException failure,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request,
                "CODING_JOB_STORE_UNAVAILABLE",
                "The authoritative coding job store is unavailable.",
                true,
                1_000L));
    }

    private static CodingJobLifecycleContract.ErrorEnvelope error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        return new CodingJobLifecycleContract.ErrorEnvelope(
                CodingJobLifecycleContract.SCHEMA_VERSION,
                traceId(request),
                null,
                new CodingJobLifecycleContract.ErrorDetail(
                        code, message, retryable, retryAfterMs));
    }

    private static UUID traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        return UUID.fromString(String.valueOf(value));
    }
}
