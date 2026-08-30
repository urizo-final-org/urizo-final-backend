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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingWorkerContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingHandlerStageService;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnAccessException;
import org.urizo.axmodulestudio.backend.coding.service.CodingToolException;
import org.urizo.axmodulestudio.backend.coding.service.CodingWorkerException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;

@RestController
@RequestMapping("/internal/coding/worker/jobs/{jobId}/attempts/{pipelineAttempt}")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingHandlerStageController {

    private final CodingHandlerStageService service;

    public CodingHandlerStageController(CodingHandlerStageService service) {
        this.service = service;
    }

    @PostMapping("/stages/{handlerKey}/executions/{resultId}")
    CodingHandlerContract.StageExecutionResponse execute(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @PathVariable String handlerKey,
            @PathVariable UUID resultId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CodingHandlerContract.StageExecutionRequest body) {
        if (!handlerKey.equals(body.handlerKey())) {
            throw new IllegalArgumentException("handlerKey does not match the path.");
        }
        return service.execute(
                authorization, jobId, pipelineAttempt, resultId, body);
    }

    @ExceptionHandler(CodingWorkerException.class)
    ResponseEntity<Object> workerFailure(
            CodingWorkerException failure, HttpServletRequest request) {
        return failure(
                request, failure.status(), failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs());
    }

    @ExceptionHandler(CodingToolException.class)
    ResponseEntity<Object> toolFailure(
            CodingToolException failure, HttpServletRequest request) {
        return failure(
                request, failure.status(), failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs());
    }

    @ExceptionHandler(CodingModelTurnAccessException.class)
    ResponseEntity<Object> accessFailure(
            CodingModelTurnAccessException failure, HttpServletRequest request) {
        return failure(
                request, failure.status(), failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs());
    }

    @ExceptionHandler(ProviderGatewayException.class)
    ResponseEntity<Object> modelFailure(
            ProviderGatewayException failure, HttpServletRequest request) {
        boolean retryable = switch (failure.code()) {
            case MODEL_RATE_LIMITED, MODEL_TIMEOUT, MODEL_PROVIDER_UNAVAILABLE,
                    INTERNAL_TRANSIENT_ERROR -> true;
            default -> false;
        };
        HttpStatus status = switch (failure.code()) {
            case CONTRACT_VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case MODEL_RESPONSE_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MODEL_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case MODEL_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return failure(
                request, status, failure.code().name(), failure.getMessage(),
                retryable, retryable ? 1_000L : null);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Object> validationFailure(
            Exception failure, HttpServletRequest request) {
        return failure(
                request, HttpStatus.BAD_REQUEST, "CONTRACT_VALIDATION_FAILED",
                "The Coding stage request does not satisfy the contract.", false, null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return failure(
                request, HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_TRANSIENT_ERROR",
                "The authoritative Coding stage store is unavailable.", true, 1_000L);
    }

    private static ResponseEntity<Object> failure(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        UUID traceId = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        UUID jobId = pathJobId(request.getRequestURI());
        CodingWorkerContract.ErrorDetail detail = new CodingWorkerContract.ErrorDetail(
                code, message, retryable, retryAfterMs);
        if (jobId != null) {
            return response.body(new CodingWorkerContract.JobErrorEnvelope(
                    CodingWorkerContract.SCHEMA_VERSION,
                    traceId,
                    jobId,
                    "stage-execution",
                    detail));
        }
        return response.body(new CodingWorkerContract.PreContextErrorEnvelope(
                CodingWorkerContract.SCHEMA_VERSION,
                UUID.randomUUID(),
                traceId,
                detail));
    }

    private static UUID pathJobId(String path) {
        try {
            String[] parts = path.split("/");
            return parts.length > 5 ? UUID.fromString(parts[5]) : null;
        }
        catch (IllegalArgumentException failure) {
            return null;
        }
    }
}
