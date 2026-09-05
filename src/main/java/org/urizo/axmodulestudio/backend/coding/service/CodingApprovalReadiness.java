package org.urizo.axmodulestudio.backend.coding.service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

/** Current-state approval authority projected from the accepted Worker outcome. */
final class CodingApprovalReadiness {

    private static final Set<String> OUTCOME_FIELDS = Set.of(
            "schemaVersion", "jobId", "traceId", "stateVersion", "status", "pendingApproval");
    private static final Set<String> APPROVAL_FIELDS = Set.of(
            "approvalId", "nodeId", "stage", "stageRound", "requiredRole");
    private static final Set<String> REQUIRED_ROLES = Set.of("GENERAL_ADMIN", "SUPER_ADMIN");
    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

    private CodingApprovalReadiness() { }

    static Optional<ReadyApproval> find(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            UUID jobId,
            UUID traceId,
            int stateVersion,
            int pipelineAttempt) {
        List<String> outcomes = jdbc.query("""
                SELECT response_json::text AS response_json
                FROM app.coding_worker_command
                WHERE command_type = 'OUTCOME'
                  AND job_id = ?
                  AND response_json ->> 'status' = 'WAITING_APPROVAL'
                  AND response_json ->> 'jobId' = ?
                  AND response_json ->> 'traceId' = ?
                  AND response_json ->> 'stateVersion' = ?
                ORDER BY created_at DESC, command_id DESC
                LIMIT 2
                """, (rs, row) -> rs.getString("response_json"),
                jobId,
                jobId.toString(),
                traceId.toString(),
                Integer.toString(stateVersion));
        if (outcomes.size() != 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(decode(
                jdbc,
                objectMapper,
                jobId,
                traceId,
                stateVersion,
                pipelineAttempt,
                outcomes.get(0)));
    }

    private static ReadyApproval decode(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            UUID jobId,
            UUID traceId,
            int stateVersion,
            int pipelineAttempt,
            String encoded) {
        try {
            JsonNode raw = objectMapper.readTree(encoded);
            if (!(raw instanceof ObjectNode outcome)
                    || !fields(outcome).equals(OUTCOME_FIELDS)
                    || !"1.0".equals(text(outcome, "schemaVersion"))
                    || !jobId.toString().equals(text(outcome, "jobId"))
                    || !traceId.toString().equals(text(outcome, "traceId"))
                    || !"WAITING_APPROVAL".equals(text(outcome, "status"))
                    || !outcome.path("stateVersion").canConvertToInt()
                    || outcome.path("stateVersion").intValue() != stateVersion
                    || !(outcome.get("pendingApproval") instanceof ObjectNode pending)
                    || !fields(pending).equals(APPROVAL_FIELDS)) {
                return null;
            }

            String approvalIdValue = text(pending, "approvalId");
            String nodeId = text(pending, "nodeId");
            String stageValue = text(pending, "stage");
            String requiredRole = text(pending, "requiredRole");
            if (approvalIdValue == null
                    || nodeId == null
                    || !NODE_ID.matcher(nodeId).matches()
                    || stageValue == null
                    || !REQUIRED_ROLES.contains(requiredRole)
                    || !pending.path("stageRound").canConvertToInt()
                    || pending.path("stageRound").intValue() < 1) {
                return null;
            }
            UUID approvalId = UUID.fromString(approvalIdValue);
            CodingHandlerContract.ApprovalStage stage =
                    CodingHandlerContract.ApprovalStage.valueOf(stageValue);
            int stageRound = pending.path("stageRound").intValue();
            if (!approvalId.equals(CodingApprovalId.forStage(
                    jobId, pipelineAttempt, nodeId, stage, stageRound))) {
                return null;
            }
            Subject subject = subject(jdbc, jobId, pipelineAttempt, stage);
            if (subject == null) {
                return null;
            }
            return new ReadyApproval(
                    approvalId,
                    nodeId,
                    stage,
                    stageRound,
                    requiredRole,
                    subject.candidateSha(),
                    subject.validationHash());
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            return null;
        }
    }

    private static Subject subject(
            JdbcTemplate jdbc,
            UUID jobId,
            int pipelineAttempt,
            CodingHandlerContract.ApprovalStage stage) {
        if (stage == CodingHandlerContract.ApprovalStage.SCOPE) {
            return latestPort(jdbc, jobId, pipelineAttempt, "coding.analyze", "feasible")
                    ? new Subject(null, null) : null;
        }
        Subject preview = latestPreview(jdbc, jobId, pipelineAttempt);
        if (preview == null) {
            return null;
        }
        if (stage == CodingHandlerContract.ApprovalStage.DEPLOY) {
            Subject deploymentRequest = latestDeploymentRequest(jdbc, jobId, pipelineAttempt);
            return deploymentRequest == null ? preview : deploymentRequest;
        }
        if (stage != CodingHandlerContract.ApprovalStage.GITHUB) {
            return preview;
        }
        Subject pullRequest = latestPullRequest(jdbc, jobId, pipelineAttempt);
        return preview.equals(pullRequest) ? preview : null;
    }

    private static boolean latestPort(
            JdbcTemplate jdbc,
            UUID jobId,
            int pipelineAttempt,
            String handlerKey,
            String expectedPort) {
        List<String> ports = jdbc.query("""
                SELECT result_port
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ? AND handler_key = ?
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """, (rs, row) -> rs.getString("result_port"),
                jobId, pipelineAttempt, handlerKey);
        return ports.size() == 1 && expectedPort.equals(ports.get(0));
    }

    private static Subject latestPreview(
            JdbcTemplate jdbc, UUID jobId, int pipelineAttempt) {
        return latestSubject(jdbc, jobId, pipelineAttempt, """
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.preview'
                  AND result_type = 'DIFF'
                  AND result_port = 'ready'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """);
    }

    private static Subject latestPullRequest(
            JdbcTemplate jdbc, UUID jobId, int pipelineAttempt) {
        Subject completed = latestSubject(jdbc, jobId, pipelineAttempt, """
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.pr_complete'
                  AND result_type = 'PULL_REQUEST'
                  AND result_port = 'completed'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """);
        return completed != null ? completed : latestSubject(jdbc, jobId, pipelineAttempt, """
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.pr_request'
                  AND result_type = 'PULL_REQUEST'
                  AND result_port = 'requested'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """);
    }

    private static Subject latestDeploymentRequest(
            JdbcTemplate jdbc, UUID jobId, int pipelineAttempt) {
        return latestSubject(jdbc, jobId, pipelineAttempt, """
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.deploy_request'
                  AND result_type = 'DEPLOY_REQUEST'
                  AND result_port = 'recorded'
                  AND payload ? 'deploymentRequestId'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """);
    }

    private static Subject latestSubject(
            JdbcTemplate jdbc, UUID jobId, int pipelineAttempt, String sql) {
        List<Subject> subjects = jdbc.query(sql, (rs, row) -> new Subject(
                rs.getString("candidate_sha"),
                rs.getString("validation_hash")), jobId, pipelineAttempt);
        if (subjects.size() != 1
                || subjects.get(0).candidateSha() == null
                || subjects.get(0).validationHash() == null) {
            return null;
        }
        return subjects.get(0);
    }

    private static Set<String> fields(ObjectNode value) {
        Set<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String text(ObjectNode value, String field) {
        JsonNode item = value.get(field);
        return item != null && item.isTextual() ? item.textValue() : null;
    }

    record ReadyApproval(
            UUID approvalId,
            String nodeId,
            CodingHandlerContract.ApprovalStage stage,
            int stageRound,
            String requiredRole,
            String candidateSha,
            String validationHash) { }

    private record Subject(String candidateSha, String validationHash) { }
}
