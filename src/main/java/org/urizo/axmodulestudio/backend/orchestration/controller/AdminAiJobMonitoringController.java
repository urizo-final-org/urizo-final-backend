package org.urizo.axmodulestudio.backend.orchestration.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorDetail;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorResponse;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService;
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService.SelectedObservationsResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobListResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobSnapshotResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrence;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringException;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringService;

@RestController
@Profile("local-full")
@RequestMapping("/api/admin/ai/monitoring")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled",
        havingValue = "true")
public class AdminAiJobMonitoringController {

    private final AiJobMonitoringService monitoring;
    private final LangfuseObservabilityService observability;

    public AdminAiJobMonitoringController(
            AiJobMonitoringService monitoring,
            LangfuseObservabilityService observability) {
        this.monitoring = monitoring;
        this.observability = observability;
    }

    @GetMapping("/jobs")
    JobListResponse jobs(@RequestParam(required = false) Integer limit) {
        return monitoring.list(limit);
    }

    @GetMapping("/jobs/{jobId}")
    JobSnapshotResponse job(
            @PathVariable UUID jobId,
            @RequestParam(required = false) Integer limit) {
        return monitoring.snapshot(jobId, limit);
    }

    @GetMapping("/jobs/{jobId}/occurrences/{pipelineAttempt}/{executionAttempt}/"
            + "{nodeSequence}/observations")
    SelectedObservationsResponse observations(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @PathVariable int executionAttempt,
            @PathVariable long nodeSequence) {
        NodeOccurrence occurrence = monitoring.requireOccurrence(
                jobId, pipelineAttempt, executionAttempt, nodeSequence);
        return observability.selectedObservations(
                jobId.toString(), occurrence.traceId().toString(),
                occurrence.profileVersionId().toString(),
                pipelineAttempt, executionAttempt, occurrence.nodeId(), nodeSequence,
                occurrence.observationTraceId(), occurrence.startedAt(),
                occurrence.lastUpdatedAt());
    }

    @ExceptionHandler(AiJobMonitoringException.class)
    ResponseEntity<ErrorResponse> monitoringFailure(
            AiJobMonitoringException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request, failure.code(), failure.getMessage(), false, null));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
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
