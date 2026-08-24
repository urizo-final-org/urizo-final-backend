package org.urizo.axmodulestudio.backend.coding.controller;

import org.urizo.axmodulestudio.backend.coding.dto.CodingWorkerContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingWorkerException;
import org.urizo.axmodulestudio.backend.coding.service.CodingWorkerService;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestController
@Validated
@RequestMapping("/internal/coding/worker/jobs/{jobId}")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingWorkerController {

    private static final String ERROR_JOB_ID = CodingWorkerController.class.getName() + ".jobId";
    private static final String ERROR_TRACE_ID = CodingWorkerController.class.getName() + ".traceId";
    private static final String ERROR_IDEMPOTENCY_KEY =
            CodingWorkerController.class.getName() + ".idempotencyKey";

    private final CodingWorkerService service;

    public CodingWorkerController(CodingWorkerService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    CodingWorkerContract.ClaimResponse claim(
            @PathVariable UUID jobId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingWorkerContract.ClaimRequest body,
            HttpServletRequest request) {
        bindErrorContext(request, body.jobId(), body.traceId(), body.idempotencyKey());
        requirePath(jobId, body.jobId());
        return service.claim(authorization, body);
    }

    @PostMapping("/heartbeat")
    CodingWorkerContract.HeartbeatResponse heartbeat(
            @PathVariable UUID jobId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingWorkerContract.HeartbeatRequest body,
            HttpServletRequest request) {
        bindErrorContext(request, body.jobId(), body.traceId(), body.idempotencyKey());
        requirePath(jobId, body.jobId());
        return service.heartbeat(authorization, body);
    }

    @PostMapping("/outcomes")
    CodingWorkerContract.OutcomeResponse outcome(
            @PathVariable UUID jobId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingWorkerContract.OutcomeRequest body,
            HttpServletRequest request) {
        bindErrorContext(request, body.jobId(), body.traceId(), body.idempotencyKey());
        requirePath(jobId, body.jobId());
        return service.outcome(authorization, body);
    }

    @ExceptionHandler(CodingWorkerException.class)
    ResponseEntity<Object> workerFailure(
            CodingWorkerException failure, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(error(request, failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Object> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request, "CONTRACT_VALIDATION_FAILED",
                "The coding worker request does not satisfy the contract.", false, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request, "INTERNAL_TRANSIENT_ERROR",
                "The authoritative coding worker store is unavailable.", true, 1_000L));
    }

    private static void requirePath(UUID path, UUID body) {
        if (!path.equals(body)) {
            throw new IllegalArgumentException("Path and body jobId must match.");
        }
    }

    private static void bindErrorContext(
            HttpServletRequest request, UUID jobId, UUID traceId, String idempotencyKey) {
        request.setAttribute(ERROR_JOB_ID, jobId);
        request.setAttribute(ERROR_TRACE_ID, traceId);
        request.setAttribute(ERROR_IDEMPOTENCY_KEY, idempotencyKey);
    }

    private static Object error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        CodingWorkerContract.ErrorDetail detail =
                new CodingWorkerContract.ErrorDetail(code, message, retryable, retryAfterMs);
        Object jobId = request.getAttribute(ERROR_JOB_ID);
        Object traceId = request.getAttribute(ERROR_TRACE_ID);
        Object idempotencyKey = request.getAttribute(ERROR_IDEMPOTENCY_KEY);
        if (jobId instanceof UUID job && traceId instanceof UUID trace
                && idempotencyKey instanceof String key) {
            return new CodingWorkerContract.JobErrorEnvelope(
                    CodingWorkerContract.SCHEMA_VERSION, trace, job, key, detail);
        }
        UUID requestTrace = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        return new CodingWorkerContract.PreContextErrorEnvelope(
                CodingWorkerContract.SCHEMA_VERSION, UUID.randomUUID(), requestTrace, detail);
    }
}
