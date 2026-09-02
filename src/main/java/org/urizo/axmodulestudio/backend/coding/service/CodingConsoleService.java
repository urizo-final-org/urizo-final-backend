package org.urizo.axmodulestudio.backend.coding.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;

/**
 * Reads a Coding Job the way the administrator console needs it.
 *
 * <p>The worker already has {@code CodingHandlerResultService.aggregate}, but that method is
 * closed behind the opaque service credential the Orchestrator holds. Borrowing that credential
 * from a browser session would be the wrong shape, so this reads the same tables directly and
 * projects them for a screen instead of for a state machine.
 *
 * <p>The role is applied here rather than in the browser. A general administrator's response
 * carries no path, sha, or diff at all, so there is nothing for the network tab to reveal.
 */
@Service
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class CodingConsoleService {

    /**
     * The pipeline currently addresses one repository. runner.ps1 knows how to check out and
     * preview the frontend, but its TEST command for the frontend is unimplemented, so a
     * frontend Job would stop before the preview a human is supposed to approve.
     */
    static final String REPOSITORY = "backend";

    /** Fixed by compose.preview.yaml. Spring has no view of the host's published ports. */
    private static final String PREVIEW_URL = "http://127.0.0.1:18081/";

    /**
     * Fixed by the schema: ck_coding_pipeline_attempt_number allows 1..3, and
     * CodingHandlerCommandService only opens a new attempt while the current one is below 3.
     * {@code worker_max_attempts} counts something else entirely - how often a worker may retry
     * a lease - and merely happens to default to the same number.
     */
    private static final int MAX_PIPELINE_ATTEMPTS = 3;

    private static final Map<String, String> STAGE_LABELS = Map.of(
            "coding.analyze", "요구사항 분석",
            "coding.code", "코드 작성",
            "coding.review", "코드 검토",
            "coding.preview", "미리보기 준비",
            "coding.pr_request", "PR 요청",
            "coding.deploy_request", "배포 요청");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // app.coding_* is granted to ai_workspace only; the primary cms_app datasource cannot
    // read a single one of these tables. Unit tests mock the template and never notice, so the
    // qualifier is the whole difference between this class working and failing at every query.
    CodingConsoleService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Newest first. A console list is read far more often than it is paged. */
    public CodingConsoleContract.JobList list(int limit) {
        List<CodingConsoleContract.JobSummary> items = jdbc.query("""
                SELECT cj.job_id, cj.status, cjr.request_text, cj.created_at, cj.finished_at,
                       COALESCE(latest.handler_key, cj.graph_step) AS stage
                FROM app.coding_job cj
                LEFT JOIN app.coding_job_request cjr ON cjr.job_id = cj.job_id
                -- graph_step stops at the node the Job entered, not the one it reached, so a
                -- finished pipeline still reports 'analyze'. The newest recorded result is the
                -- only honest answer to "where is this now".
                LEFT JOIN LATERAL (
                    SELECT chr.handler_key
                    FROM app.coding_handler_result chr
                    WHERE chr.job_id = cj.job_id
                    ORDER BY chr.recorded_at DESC, chr.result_id DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE cj.job_type = 'CODING_AGENT'
                ORDER BY cj.created_at DESC
                LIMIT ?
                """,
                (rs, row) -> new CodingConsoleContract.JobSummary(
                        rs.getObject("job_id", UUID.class),
                        REPOSITORY,
                        rs.getString("request_text"),
                        rs.getString("status"),
                        stageLabel(rs.getString("stage")),
                        instant(rs, "created_at"),
                        instant(rs, "finished_at")),
                limit);
        return new CodingConsoleContract.JobList("1.0", items);
    }

    /** Returns null when no such Job exists, so the controller can answer 404 plainly. */
    public CodingConsoleContract.JobDetail detail(UUID jobId, AdminRole role) {
        List<JobRow> jobs = jdbc.query("""
                SELECT cj.job_id, cj.trace_id, cj.status, cj.state_version, cj.graph_step,
                       cj.base_sha,
                       cj.created_at, cj.finished_at, cjr.request_text
                FROM app.coding_job cj
                LEFT JOIN app.coding_job_request cjr ON cjr.job_id = cj.job_id
                WHERE cj.job_id = ?
                """,
                (rs, row) -> new JobRow(
                        rs.getObject("trace_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("state_version"),
                        rs.getString("graph_step"),
                        rs.getString("base_sha"),
                        rs.getString("request_text"),
                        instant(rs, "created_at"),
                        instant(rs, "finished_at")),
                jobId);
        if (jobs.isEmpty()) {
            return null;
        }
        JobRow job = jobs.get(0);

        int attempt = latestAttempt(jobId);
        List<ResultRow> results = results(jobId, attempt);

        JsonNode analysis = payloadOf(results, "coding.analyze");
        JsonNode review = payloadOf(results, "coding.review");
        JsonNode preview = payloadOf(results, "coding.preview");

        return new CodingConsoleContract.JobDetail(
                "1.0",
                jobId,
                REPOSITORY,
                job.requestText(),
                job.status(),
                stageLabel(results.isEmpty() ? job.graphStep() : results.get(0).handlerKey()),
                attempt,
                MAX_PIPELINE_ATTEMPTS,
                plan(analysis),
                report(review),
                pendingApproval(job, jobId, attempt),
                decisions(jobId),
                new CodingConsoleContract.PreviewLink(preview != null, preview == null ? null : PREVIEW_URL),
                // Everything a general administrator must not read lives in this one object,
                // and for them it is simply absent from the response.
                role == AdminRole.SUPER_ADMIN ? technical(job, results, preview) : null,
                job.createdAt(),
                job.finishedAt());
    }

    private CodingConsoleContract.Plan plan(JsonNode analysis) {
        if (analysis == null) {
            return null;
        }
        return new CodingConsoleContract.Plan(
                text(analysis, "planSummary"),
                strings(analysis.path("acceptanceCriteria")));
    }

    private CodingConsoleContract.Report report(JsonNode review) {
        if (review == null) {
            return null;
        }
        List<CodingConsoleContract.CriterionResult> verdicts = new ArrayList<>();
        JsonNode array = review.path("criteriaResults");
        if (array.isArray()) {
            for (JsonNode item : array) {
                String criterion = text(item, "criterion");
                if (criterion == null) {
                    continue;
                }
                JsonNode met = item.path("met");
                verdicts.add(new CodingConsoleContract.CriterionResult(
                        criterion, met.isBoolean() ? met.asBoolean() : null));
            }
        }
        return new CodingConsoleContract.Report(text(review, "reportSummary"), verdicts);
    }

    private CodingConsoleContract.Technical technical(
            JobRow job, List<ResultRow> results, JsonNode preview) {
        ResultRow candidate = results.stream()
                .filter(row -> "coding.preview".equals(row.handlerKey()))
                .findFirst()
                .orElseGet(() -> results.stream()
                        .filter(row -> "coding.code".equals(row.handlerKey()))
                        .findFirst()
                        .orElse(null));
        return new CodingConsoleContract.Technical(
                job.baseSha(),
                candidate == null ? null : candidate.candidateSha(),
                candidate == null ? null : candidate.diffDigest(),
                preview == null ? List.of() : strings(preview.path("changedPaths")),
                preview == null ? null : text(preview, "checkProfile"),
                null,
                null);
    }

    private List<CodingConsoleContract.DecisionRecord> decisions(UUID jobId) {
        return jdbc.query("""
                SELECT stage, decision, actor_role, feedback, decided_at
                FROM app.coding_approval_decision
                WHERE job_id = ?
                ORDER BY decided_at
                """,
                (rs, row) -> new CodingConsoleContract.DecisionRecord(
                        rs.getString("stage"),
                        rs.getString("decision"),
                        rs.getString("actor_role"),
                        rs.getString("feedback"),
                        instant(rs, "decided_at")),
                jobId);
    }

    private int latestAttempt(UUID jobId) {
        Integer attempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(pipeline_attempt), 1)
                FROM app.coding_pipeline_attempt
                WHERE job_id = ?
                """, Integer.class, jobId);
        return attempt == null ? 1 : attempt;
    }

    /**
     * The approval the Job is waiting on, computed by the same readiness the decision endpoint
     * enforces. Deriving it twice by two rules would let the screen offer a decision the server
     * then rejects; sharing the rule means what the screen shows is what will be accepted.
     *
     * <p>Empty for every Job that is not waiting, which is the common case.
     */
    private CodingConsoleContract.PendingApproval pendingApproval(
            JobRow job, UUID jobId, int attempt) {
        return CodingApprovalReadiness.find(
                        jdbc, objectMapper, jobId, job.traceId(), job.stateVersion(), attempt)
                .map(ready -> new CodingConsoleContract.PendingApproval(
                        ready.approvalId(),
                        job.traceId(),
                        ready.nodeId(),
                        ready.stage().name(),
                        ready.stageRound(),
                        ready.requiredRole(),
                        job.stateVersion(),
                        attempt,
                        ready.candidateSha(),
                        ready.validationHash()))
                .orElse(null);
    }

    private List<ResultRow> results(UUID jobId, int attempt) {
        return jdbc.query("""
                SELECT handler_key, result_port, candidate_sha, diff_digest, payload::text
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                ORDER BY recorded_at DESC, result_id DESC
                """,
                (rs, row) -> new ResultRow(
                        rs.getString("handler_key"),
                        rs.getString("candidate_sha"),
                        rs.getString("diff_digest"),
                        readTree(rs.getString("payload"))),
                jobId, attempt);
    }

    /** Rows arrive newest first, so the first match is the current one for that stage. */
    private JsonNode payloadOf(List<ResultRow> results, String handlerKey) {
        return results.stream()
                .filter(row -> handlerKey.equals(row.handlerKey()))
                .map(ResultRow::payload)
                .filter(payload -> payload != null && payload.isObject())
                .findFirst()
                .orElse(null);
    }

    private JsonNode readTree(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            // The column is jsonb, so this cannot be malformed. Treating it as absent keeps a
            // single unreadable row from hiding the whole request from its approver.
            return null;
        }
    }

    /** Null rather than an empty string: the screen distinguishes "not written" from "blank". */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String result = value.asText().strip();
        return result.isEmpty() ? null : result;
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().strip());
            }
        }
        return List.copyOf(values);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String stageLabel(String graphStep) {
        if (graphStep == null) {
            return null;
        }
        return STAGE_LABELS.getOrDefault(graphStep, graphStep);
    }

    private record JobRow(
            UUID traceId,
            String status,
            int stateVersion,
            String graphStep,
            String baseSha,
            String requestText,
            Instant createdAt,
            Instant finishedAt) { }

    private record ResultRow(
            String handlerKey,
            String candidateSha,
            String diffDigest,
            JsonNode payload) { }
}
