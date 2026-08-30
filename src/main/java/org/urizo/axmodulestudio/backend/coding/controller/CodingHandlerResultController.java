package org.urizo.axmodulestudio.backend.coding.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingWorkerContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingHandlerResultService;
import org.urizo.axmodulestudio.backend.coding.service.CodingWorkerException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestController
@Validated
@RequestMapping("/internal/coding/worker/jobs/{jobId}/attempts/{pipelineAttempt}")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingHandlerResultController {

    private final CodingHandlerResultService service;

    public CodingHandlerResultController(CodingHandlerResultService service) {
        this.service = service;
    }

    @PutMapping("/results/{resultId}")
    CodingHandlerContract.HandlerResultResponse put(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @PathVariable UUID resultId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingHandlerContract.PutResultRequest body) {
        return service.put(authorization, jobId, pipelineAttempt, resultId, body);
    }

    @GetMapping("/results/{resultId}")
    CodingHandlerContract.HandlerResultResponse get(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @PathVariable UUID resultId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return service.get(authorization, jobId, pipelineAttempt, resultId);
    }

    @GetMapping
    CodingHandlerContract.AttemptAggregateResponse aggregate(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return service.aggregate(authorization, jobId, pipelineAttempt);
    }

    @ExceptionHandler(CodingWorkerException.class)
    ResponseEntity<Object> workerFailure(
            CodingWorkerException failure, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(error(
                request, failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Object> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request,
                "CONTRACT_VALIDATION_FAILED",
                "The Coding Handler result request does not satisfy the contract.",
                false,
                null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request,
                "INTERNAL_TRANSIENT_ERROR",
                "The authoritative Coding Handler result store is unavailable.",
                true,
                1_000L));
    }

    private static Object error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        CodingWorkerContract.ErrorDetail detail =
                new CodingWorkerContract.ErrorDetail(code, message, retryable, retryAfterMs);
        String[] path = request.getRequestURI().split("/");
        UUID jobId = path.length > 5 ? parse(path[5]) : null;
        UUID requestTrace = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        if (jobId != null) {
            return new CodingWorkerContract.JobErrorEnvelope(
                    CodingWorkerContract.SCHEMA_VERSION,
                    requestTrace,
                    jobId,
                    "handler-result",
                    detail);
        }
        return new CodingWorkerContract.PreContextErrorEnvelope(
                CodingWorkerContract.SCHEMA_VERSION,
                UUID.randomUUID(),
                requestTrace,
                detail);
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException failure) {
            return null;
        }
    }
}
