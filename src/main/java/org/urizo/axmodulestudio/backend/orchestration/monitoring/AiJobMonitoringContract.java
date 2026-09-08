package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AiJobMonitoringContract {

    public static final String SCHEMA_VERSION = "1.0";
    private static final String IDENTIFIER = "^[A-Za-z][A-Za-z0-9_.-]{0,63}$";
    private static final String HANDLER_KEY = "^[a-z][a-z0-9_.-]{0,127}$";
    private static final String ERROR_CODE = "^[A-Z][A-Z0-9_]{2,119}$";
    private static final String OBSERVATION_TRACE_ID = "^[0-9a-f]{32}$";

    private AiJobMonitoringContract() { }

    public enum NodeStatus {
        RUNNING,
        WAITING_APPROVAL,
        COMPLETED,
        FAILED
    }

    public enum NodeDisplayStatus {
        NOT_STARTED,
        RUNNING,
        WAITING_APPROVAL,
        COMPLETED,
        FAILED
    }

    public record NodeOccurrenceReport(
            @NotBlank @Size(max = 8) String schemaVersion,
            @NotNull UUID jobId,
            @NotNull UUID traceId,
            @Pattern(regexp = OBSERVATION_TRACE_ID) String observationTraceId,
            @NotNull UUID profileVersionId,
            @Min(1) int pipelineAttempt,
            @Min(1) int executionAttempt,
            @NotBlank @Pattern(regexp = IDENTIFIER) String nodeId,
            @Min(1) long nodeSequence,
            @NotBlank @Pattern(regexp = IDENTIFIER) String nodeType,
            @NotBlank @Pattern(regexp = HANDLER_KEY) String handlerKey,
            @NotNull NodeStatus status,
            @NotNull Instant occurredAt,
            @Pattern(regexp = ERROR_CODE) String errorCode) {

        public NodeOccurrenceReport {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("Unsupported monitoring schemaVersion.");
            }
            boolean failed = status == NodeStatus.FAILED;
            if (failed != (errorCode != null)) {
                throw new IllegalArgumentException(
                        "errorCode must be present only for FAILED monitoring reports.");
            }
        }
    }

    public record ReportResponse(
            String schemaVersion,
            long monitorRevision,
            boolean applied,
            boolean current,
            NodeStatus status,
            Instant updatedAt) { }

    public record JobListResponse(
            String schemaVersion,
            Instant observedAt,
            List<JobSummary> jobs) { }

    public record JobSnapshotResponse(
            String schemaVersion,
            Instant observedAt,
            JobSummary job,
            List<LatestNodeState> latestNodeStates,
            List<NodeOccurrence> occurrences,
            boolean truncated) { }

    public record JobSummary(
            UUID jobId,
            UUID traceId,
            UUID profileVersionId,
            String profileKey,
            int profileVersion,
            String domainJobStatus,
            boolean domainTerminal,
            long stateVersion,
            int pipelineAttempt,
            int executionAttempt,
            NodeStatus monitorStatus,
            long monitorRevision,
            CurrentNode currentNode,
            String profileSnapshotPath,
            String profileLayoutPath,
            Instant lastUpdatedAt) { }

    public record CurrentNode(
            String nodeId,
            long nodeSequence,
            String nodeType,
            String handlerKey,
            NodeStatus status) { }

    public record LatestNodeState(
            String nodeId,
            String nodeType,
            String handlerKey,
            NodeDisplayStatus status,
            Integer pipelineAttempt,
            Integer executionAttempt,
            Long nodeSequence,
            Instant lastUpdatedAt) { }

    public record NodeOccurrence(
            UUID jobId,
            UUID profileVersionId,
            int pipelineAttempt,
            int executionAttempt,
            String nodeId,
            long nodeSequence,
            UUID traceId,
            String observationTraceId,
            String nodeType,
            String handlerKey,
            NodeStatus status,
            Instant startedAt,
            Instant waitingAt,
            Instant completedAt,
            Instant failedAt,
            String errorCode,
            String observationsPath,
            Instant lastUpdatedAt) { }
}
