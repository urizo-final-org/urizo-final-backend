package org.urizo.axmodulestudio.backend.coding.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.urizo.axmodulestudio.backend.coding.dto.CodingRunnerContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingRunnerService;
import org.urizo.axmodulestudio.backend.coding.service.CodingWorkerException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestController
@Validated
@RequestMapping("/internal/coding/runner")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingRunnerController {

    private static final Logger log = LoggerFactory.getLogger(CodingRunnerController.class);
    private static final String ERROR_TASK_ID = CodingRunnerController.class.getName() + ".taskId";
    private static final String ERROR_TRACE_ID = CodingRunnerController.class.getName() + ".traceId";

    private final CodingRunnerService service;

    public CodingRunnerController(CodingRunnerService service) {
        this.service = service;
    }

    @PostMapping("/tasks/claim")
    ResponseEntity<CodingRunnerContract.ClaimResponse> claim(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingRunnerContract.ClaimRequest body,
            HttpServletRequest request) {
        bindErrorContext(request, null, body.traceId());
        CodingRunnerContract.ClaimResponse claimed = service.claim(authorization, body);
        if (claimed == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(claimed);
    }

    @PostMapping("/heartbeat")
    CodingRunnerContract.HeartbeatResponse heartbeat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingRunnerContract.HeartbeatRequest body,
            HttpServletRequest request) {
        bindErrorContext(request, body.taskId(), body.traceId());
        return service.heartbeat(authorization, body);
    }

    @PostMapping("/tasks/{taskId}/outcomes")
    CodingRunnerContract.OutcomeResponse outcome(
            @PathVariable UUID taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingRunnerContract.OutcomeRequest body,
            HttpServletRequest request) {
        bindErrorContext(request, body.taskId(), body.traceId());
        if (!taskId.equals(body.taskId())) {
            throw new IllegalArgumentException("Path and body taskId must match.");
        }
        return service.outcome(authorization, body);
    }

    @ExceptionHandler(CodingWorkerException.class)
    ResponseEntity<Object> runnerFailure(
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
                "The coding runner request does not satisfy the contract.", false, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        // Without this the caller only ever sees "the store is unavailable", which reads as a
        // transient database problem even when the real cause is a broken statement.
        // Bind values are never rendered by the driver, so no credential material is logged.
        log.warn("Runner task store access failed: {}", failure.getMostSpecificCause().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request, "INTERNAL_TRANSIENT_ERROR",
                "The authoritative runner task store is unavailable.", true, 1_000L));
    }

    private static void bindErrorContext(
            HttpServletRequest request, UUID taskId, UUID traceId) {
        request.setAttribute(ERROR_TASK_ID, taskId);
        request.setAttribute(ERROR_TRACE_ID, traceId);
    }

    private static Object error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        CodingRunnerContract.ErrorDetail detail =
                new CodingRunnerContract.ErrorDetail(code, message, retryable, retryAfterMs);
        Object taskId = request.getAttribute(ERROR_TASK_ID);
        Object traceId = request.getAttribute(ERROR_TRACE_ID);
        UUID resolvedTrace = traceId instanceof UUID trace ? trace : UUID.fromString(
                String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        return new CodingRunnerContract.RunnerErrorEnvelope(
                CodingRunnerContract.SCHEMA_VERSION, resolvedTrace,
                taskId instanceof UUID task ? task : null, detail);
    }
}