package org.urizo.axmodulestudio.backend.coding.controller;

import org.urizo.axmodulestudio.backend.coding.dto.CodingToolContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingToolException;
import org.urizo.axmodulestudio.backend.coding.service.CodingToolService;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestController
@RequestMapping("/internal/coding")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
final class CodingToolController {

    private static final String ERROR_REQUEST_ID = CodingToolController.class.getName() + ".requestId";
    private static final String ERROR_JOB_ID = CodingToolController.class.getName() + ".jobId";
    private static final String ERROR_TRACE_ID = CodingToolController.class.getName() + ".traceId";
    private static final String ERROR_IDEMPOTENCY_KEY =
            CodingToolController.class.getName() + ".idempotencyKey";
    private static final String ERROR_EXECUTION_ID =
            CodingToolController.class.getName() + ".executionId";

    private final CodingToolService service;

    CodingToolController(CodingToolService service) {
        this.service = service;
    }

    @PostMapping("/tool-requests")
    ResponseEntity<CodingToolContract.Accepted> submit(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        bindJobContext(request, body);
        CodingToolContract.Accepted accepted = service.submit(authorization, body);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, accepted.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(accepted);
    }

    @GetMapping("/tool-executions/{executionId}")
    CodingToolContract.Succeeded execution(
            @PathVariable UUID executionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        request.setAttribute(ERROR_REQUEST_ID, UUID.randomUUID());
        request.setAttribute(ERROR_EXECUTION_ID, executionId);
        return service.execution(authorization, executionId);
    }

    @GetMapping("/tool-executions/{executionId}/result")
    CodingToolContract.ResultContent result(
            @PathVariable UUID executionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        request.setAttribute(ERROR_REQUEST_ID, UUID.randomUUID());
        request.setAttribute(ERROR_EXECUTION_ID, executionId);
        return service.result(authorization, executionId);
    }

    @ExceptionHandler(CodingToolException.class)
    ResponseEntity<Object> toolFailure(
            CodingToolException failure, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(error(request, failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<Object> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request, "TOOL_ARGUMENTS_INVALID",
                "The tool request does not satisfy the contract.", false, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request, "TOOL_EXECUTOR_UNAVAILABLE",
                "Tool Gateway is unavailable.", true, 1_000L));
    }

    private static void bindJobContext(HttpServletRequest request, JsonNode body) {
        try {
            request.setAttribute(ERROR_REQUEST_ID, UUID.fromString(body.path("requestId").textValue()));
            request.setAttribute(ERROR_JOB_ID, UUID.fromString(body.path("jobId").textValue()));
            request.setAttribute(ERROR_TRACE_ID, UUID.fromString(body.path("traceId").textValue()));
            String key = body.path("idempotencyKey").textValue();
            if (key != null && !key.isBlank()) {
                request.setAttribute(ERROR_IDEMPOTENCY_KEY, key);
            }
        }
        catch (IllegalArgumentException | NullPointerException ignored) {
            // Invalid correlation fields are handled by the strict service parser;
            // the exception handler will use the canonical pre-context envelope.
        }
    }

    private static Object error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        CodingToolContract.ErrorDetail detail =
                new CodingToolContract.ErrorDetail(code, message, retryable, retryAfterMs);
        Object jobId = request.getAttribute(ERROR_JOB_ID);
        Object traceId = request.getAttribute(ERROR_TRACE_ID);
        Object key = request.getAttribute(ERROR_IDEMPOTENCY_KEY);
        if (jobId instanceof UUID job && traceId instanceof UUID trace
                && key instanceof String idempotencyKey) {
            return new CodingToolContract.JobErrorEnvelope(
                    CodingToolContract.SCHEMA_VERSION, trace, job, idempotencyKey, detail);
        }
        UUID requestTrace = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        Object executionId = request.getAttribute(ERROR_EXECUTION_ID);
        Object requestId = request.getAttribute(ERROR_REQUEST_ID);
        if (executionId instanceof UUID execution && requestId instanceof UUID correlation) {
            return new CodingToolContract.ExecutionErrorEnvelope(
                    CodingToolContract.SCHEMA_VERSION, correlation, requestTrace, execution, detail);
        }
        UUID correlation = requestId instanceof UUID value ? value : UUID.randomUUID();
        return new CodingToolContract.PreContextErrorEnvelope(
                CodingToolContract.SCHEMA_VERSION, correlation, requestTrace, detail);
    }
}
