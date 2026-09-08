package org.urizo.axmodulestudio.backend.orchestration.controller;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorDetail;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorResponse;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrenceReport;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.ReportResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringException;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringService;

@RestController
@RequestMapping("/internal/ai/monitoring")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled",
        havingValue = "true")
public class InternalAiJobMonitoringController {

    private final AiJobMonitoringService service;

    public InternalAiJobMonitoringController(AiJobMonitoringService service) {
        this.service = service;
    }

    @PostMapping("/node-occurrences")
    ReportResponse report(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Valid @RequestBody NodeOccurrenceReport body) {
        return service.report(authorization, body);
    }

    @ExceptionHandler(AiJobMonitoringException.class)
    ResponseEntity<ErrorResponse> monitoringFailure(
            AiJobMonitoringException failure, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(error(request, failure.code(), failure.getMessage(), false, null));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ErrorResponse> malformedRequest(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request, "CONTRACT_VALIDATION_FAILED",
                "The request does not satisfy the monitoring contract.", false, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request, "INTERNAL_TRANSIENT_ERROR",
                "The monitoring store is unavailable.", true, 1_000L));
    }

    private static ErrorResponse error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        UUID traceId = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        return new ErrorResponse(
                "1.0", traceId, new ErrorDetail(code, message, retryable, retryAfterMs));
    }
}
