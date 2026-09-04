package org.urizo.axmodulestudio.backend.coding.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;

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

    /**
     * The states a request can still be called off from. A Job that has finished has nothing to
     * stop, and RUNNING is left out on purpose: the tool gateway and the stage authority both
     * require the Job to read RUNNING ({@code CodingToolService}), so flipping it mid-run would
     * not stop the work cleanly - it would fail the next tool call and leave the worker trying
     * to record a failure the state machine forbids. Stopping a running model needs the worker
     * to cooperate, which is a separate piece of work.
     */
    private static final Set<String> CANCELLABLE = Set.of("PENDING", "WAITING_APPROVAL");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CodingJobLifecycleService lifecycle;

    // app.coding_* is granted to ai_workspace only; the primary cms_app datasource cannot
    // read a single one of these tables. Unit tests mock the template and never notice, so the
    // qualifier is the whole difference between this class working and failing at every query.
    CodingConsoleService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CodingJobLifecycleService lifecycle) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.lifecycle = lifecycle;
    }

    /**
     * Calls off a request the administrator no longer wants.
     *
     * <p>Rejecting is not this. A rejection at the preview stage starts the next attempt - that
     * is what it is for - so a person who has changed their mind entirely has no way to say so
     * and has to reject three times, waiting through a full model run each time. Measured on
     * 2026-09-04, when the only way out was the development-only transition endpoint.
     *
     * <p>Nothing here decides anything the state machine does not already decide. The transition
     * is the same one every other path uses, so the version check, the audit row and the
     * allowed-target rules are the existing ones rather than a second copy.
     */
    public CodingConsoleContract.JobDetail cancel(
            UUID jobId, String idempotencyKey, AdminRole role) {
        List<CancelRow> rows = jdbc.query("""
                SELECT trace_id, status, state_version
                FROM app.coding_job
                WHERE job_id = ?
                """,
                (rs, row) -> new CancelRow(
                        rs.getObject("trace_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("state_version")),
                jobId);
        if (rows.isEmpty()) {
            return null;
        }
        CancelRow job = rows.get(0);
        if (!CANCELLABLE.contains(job.status())) {
            // Worded for the person reading the screen: they need to know whether waiting
            // will help, which is the only difference that matters between these two.
            throw new CodingJobLifecycleException(
                    "RUNNING".equals(job.status())
                            ? "CODING_JOB_IS_RUNNING" : "CODING_JOB_ALREADY_FINISHED",
                    "RUNNING".equals(job.status())
                            ? "지금은 AI 가 작업 중입니다. 작업이 끝나면 취소할 수 있습니다."
                            : "이미 끝난 요청은 취소할 수 없습니다.",
                    HttpStatus.CONFLICT,
                    false,
                    null);
        }
        lifecycle.transition(jobId, job.traceId(), idempotencyKey,
                new CodingJobLifecycleContract.TransitionRequest(
                        CodingJobLifecycleContract.SCHEMA_VERSION,
                        job.stateVersion(),
                        CodingJobLifecycleContract.Status.CANCELLED,
                        null));
        return detail(jobId, role);
    }

    private record CancelRow(UUID traceId, String status, int stateVersion) { }

    /** Newest first. A console list is read far more often than it is paged. */
    public CodingConsoleContract.JobList list(int limit) {
        List<CodingConsoleContract.JobSummary> items = jdbc.query("""
                SELECT cj.job_id, cj.status, cjr.request_text, cj.created_at, cj.finished_at,
                       cj.failure_code, cj.repository_id,
                       COALESCE(latest.handler_key, cj.graph_step) AS stage,
                       COALESCE(refusal.refused, FALSE) AS refused,
                       -- A review that asks for rework is always followed by another coding
                       -- round, unless there were none left. So a finished Job whose newest
                       -- word is "changes requested" is one the model gave up on.
                       (cj.finished_at IS NOT NULL
                            AND latest.handler_key = 'coding.review'
                            AND latest.result_port = 'changes_requested') AS handed_over
                FROM app.coding_job cj
                LEFT JOIN app.coding_job_request cjr ON cjr.job_id = cj.job_id
                -- graph_step stops at the node the Job entered, not the one it reached, so a
                -- finished pipeline still reports 'analyze'. The newest recorded result is the
                -- only honest answer to "where is this now".
                LEFT JOIN LATERAL (
                    SELECT chr.handler_key, chr.result_port
                    FROM app.coding_handler_result chr
                    WHERE chr.job_id = cj.job_id
                    ORDER BY chr.recorded_at DESC, chr.result_id DESC
                    LIMIT 1
                ) latest ON TRUE
                -- The analyst's own verdict. 'infeasible' is a refusal, and it is the only
                -- place the refusal is recorded: the job itself ends as an ordinary success.
                LEFT JOIN LATERAL (
                    SELECT chr.result_port = 'infeasible' AS refused
                    FROM app.coding_handler_result chr
                    WHERE chr.job_id = cj.job_id AND chr.handler_key = 'coding.analyze'
                    ORDER BY chr.recorded_at DESC, chr.result_id DESC
                    LIMIT 1
                ) refusal ON TRUE
                WHERE cj.job_type = 'CODING_AGENT'
                ORDER BY cj.created_at DESC
                LIMIT ?
                """,
                (rs, row) -> new CodingConsoleContract.JobSummary(
                        rs.getObject("job_id", UUID.class),
                        CodingRepositories.nameOf(rs.getObject("repository_id", UUID.class)),
                        rs.getString("request_text"),
                        rs.getString("status"),
                        stageLabel(rs.getString("stage")),
                        instant(rs, "created_at"),
                        instant(rs, "finished_at"),
                        rs.getString("failure_code"),
                        rs.getBoolean("refused"),
                        rs.getBoolean("handed_over")),
                limit);
        return new CodingConsoleContract.JobList("1.0", items);
    }


    /**
     * What happened while the reader was away: other people's decisions, and the approvals
     * now waiting on this reader's own role.
     *
     * <p>A person's own decision is left out. Being told what you just clicked is noise, and
     * noise is how a notification badge stops being read at all.
     *
     * <p>The decision rows carry an actor id, not a name. Names live in {@code app.admin_account},
     * which this connection has no grant on - deliberately, since the Coding console reads
     * Coding tables. The caller resolves the names through the authentication service, which
     * already holds that access.
     */
    public List<DecisionEvent> recentDecisions(UUID excludedActorId, int limit) {
        return jdbc.query("""
                SELECT cad.job_id, cad.stage, cad.decision, cad.actor_id, cad.actor_role,
                       cad.decided_at, cjr.request_text
                FROM app.coding_approval_decision cad
                LEFT JOIN app.coding_job_request cjr ON cjr.job_id = cad.job_id
                WHERE cad.actor_id <> ?
                ORDER BY cad.decided_at DESC
                LIMIT ?
                """,
                (rs, row) -> new DecisionEvent(
                        rs.getObject("job_id", UUID.class),
                        rs.getString("request_text"),
                        rs.getString("stage"),
                        rs.getString("decision"),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_role"),
                        instant(rs, "decided_at")),
                excludedActorId, limit);
    }

    /** One decision, before the actor's name has been looked up. */
    public record DecisionEvent(
            UUID jobId,
            String requestText,
            String stage,
            String decision,
            UUID actorId,
            String actorRole,
            Instant decidedAt) { }

    /**
     * The requests now waiting for someone with this role to decide.
     *
     * <p>Read from the job status rather than from a readiness computation: the console list
     * already treats WAITING_APPROVAL as the truth about what needs a person, and a second
     * definition of "waiting" would be one more thing to keep in step.
     */
    public List<CodingConsoleContract.Notification> waitingForRole(AdminRole role, int limit) {
        return jdbc.query("""
                SELECT cj.job_id, cjr.request_text, cj.updated_at,
                       COALESCE(latest.handler_key, cj.graph_step) AS stage
                FROM app.coding_job cj
                LEFT JOIN app.coding_job_request cjr ON cjr.job_id = cj.job_id
                LEFT JOIN LATERAL (
                    SELECT chr.handler_key
                    FROM app.coding_handler_result chr
                    WHERE chr.job_id = cj.job_id
                    ORDER BY chr.recorded_at DESC, chr.result_id DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE cj.job_type = 'CODING_AGENT' AND cj.status = 'WAITING_APPROVAL'
                ORDER BY cj.updated_at DESC
                LIMIT ?
                """,
                (rs, row) -> new CodingConsoleContract.Notification(
                        "APPROVAL_WAITING",
                        rs.getObject("job_id", UUID.class),
                        rs.getString("request_text"),
                        stageLabel(rs.getString("stage")),
                        null, null, null,
                        instant(rs, "updated_at")),
                limit);
    }

    /** Returns null when no such Job exists, so the controller can answer 404 plainly. */
    public CodingConsoleContract.JobDetail detail(UUID jobId, AdminRole role) {
        List<JobRow> jobs = jdbc.query("""
                SELECT cj.job_id, cj.trace_id, cj.status, cj.state_version, cj.graph_step,
                       cj.base_sha, cj.repository_id,
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
                        CodingRepositories.nameOf(rs.getObject("repository_id", UUID.class)),
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
        // Read once: the same rows answer "is there a preview to open" for one reader and
        // "why not" for the other.
        List<RunnerRow> work = runnerWork(jobId);

        return new CodingConsoleContract.JobDetail(
                "1.0",
                jobId,
                job.repository(),
                job.requestText(),
                job.status(),
                stageLabel(results.isEmpty() ? job.graphStep() : results.get(0).handlerKey()),
                attempt,
                MAX_PIPELINE_ATTEMPTS,
                plan(analysis),
                report(review),
                pendingApproval(job, jobId, attempt),
                decisions(jobId),
                handover(job.finishedAt(), results),
                previewLink(work, preview != null),
                // Everything a general administrator must not read lives in this one object,
                // and for them it is simply absent from the response.
                role == AdminRole.SUPER_ADMIN
                        ? technical(job, jobId, results, preview, work) : null,
                job.createdAt(),
                job.finishedAt(),
                refused(results));
    }

    /**
     * The record a person inherits, or nothing when nobody has to inherit anything.
     *
     * <p>Nothing writes down "this was handed over": the gate that decides it runs inside the
     * graph and records no result of its own. What it leaves behind is enough. A review that
     * asks for rework is always followed by another coding round, unless there were no rounds
     * left - so a finished Job whose newest word is "changes requested" is one the model gave
     * up on. Any other ending has a preview, an approval, or a refusal after it.
     */
    static CodingConsoleContract.Handover handover(Instant finishedAt, List<ResultRow> results) {
        if (finishedAt == null || results.isEmpty()) {
            return null;
        }
        ResultRow newest = results.get(0);
        if (!"coding.review".equals(newest.handlerKey())
                || !"changes_requested".equals(newest.resultPort())) {
            return null;
        }
        // Rows arrive newest first; a handover is read forwards, in the order it happened.
        List<CodingConsoleContract.Attempt> attempts = new ArrayList<>();
        for (int index = results.size() - 1; index >= 0; index--) {
            ResultRow row = results.get(index);
            if (!"coding.review".equals(row.handlerKey())) {
                continue;
            }
            JsonNode payload = row.payload();
            attempts.add(new CodingConsoleContract.Attempt(
                    attempts.size() + 1,
                    "passed".equals(row.resultPort()),
                    payload == null ? null : text(payload, "reportSummary"),
                    criteria(payload),
                    row.recordedAt()));
        }
        return new CodingConsoleContract.Handover(attempts.size(), attempts);
    }

    /**
     * Whether there is a preview to open, and if not, why.
     *
     * <p>The preview stage records its result and queues the Docker work; raising the stack
     * happens afterwards and can fail. Reading only the stage result told the screen "ready"
     * either way, so the person opened the previous request's preview, saw a working site and
     * approved it. The runner's own rows are the only place the answer exists.
     *
     * <p>The rows are found by the workspace they were queued with, which is this Job: a queue
     * has no job column. Read through the connection this service already uses - it is the one
     * that writes those rows, so no grant is involved.
     */
    static CodingConsoleContract.PreviewLink previewLink(
            List<RunnerRow> rows, boolean stageRecorded) {
        if (rows.isEmpty()) {
            // Queued rows carry the workspace only for Jobs created by the current intake. An
            // older Job has none, and no evidence is not evidence of failure: it keeps the
            // answer it has always given.
            return new CodingConsoleContract.PreviewLink(
                    stageRecorded, stageRecorded ? PREVIEW_URL : null, null);
        }
        RunnerRow failed = rows.stream()
                .filter(row -> "FAILED".equals(row.status()))
                .findFirst()
                .orElse(null);
        if (failed != null) {
            return new CodingConsoleContract.PreviewLink(false, null, blockedReason(failed.kind()));
        }
        boolean up = rows.stream()
                .anyMatch(row -> "PREVIEW_UP".equals(row.kind()) && "SUCCEEDED".equals(row.status()));
        return new CodingConsoleContract.PreviewLink(up, up ? PREVIEW_URL : null, null);
    }

    /**
     * Why there is nothing to open, for the screen a general administrator reads.
     *
     * <p>Deliberately without the runner's own words: those name files and carry compiler
     * codes, and showing them here is the thing approval 2 exists not to do. The detail is on
     * the super administrator's side of the response.
     */
    private static String blockedReason(String kind) {
        return switch (kind) {
            case "TEST" -> "AI 가 만든 화면이 검사를 통과하지 못했습니다. "
                    + "최고관리자에게 확인을 요청해 주세요.";
            case "BUILD" -> "AI 가 만든 결과로 미리보기를 준비하지 못했습니다. "
                    + "최고관리자에게 확인을 요청해 주세요.";
            default -> "미리보기를 띄우지 못했습니다. 최고관리자에게 확인을 요청해 주세요.";
        };
    }

    /** The newest queued Docker step of each kind for this Job, in the order they run. */
    private List<RunnerRow> runnerWork(UUID jobId) {
        return jdbc.query("""
                SELECT DISTINCT ON (kind) kind, status, error_code,
                       result_json ->> 'detail' AS detail
                FROM app.coding_runner_task
                WHERE payload ->> 'workspaceId' = ?
                  AND kind IN ('BUILD', 'TEST', 'PREVIEW_UP')
                ORDER BY kind, created_at DESC
                """,
                (rs, row) -> new RunnerRow(rs.getString("kind"), rs.getString("status"),
                        rs.getString("error_code"), rs.getString("detail")),
                jobId.toString());
    }

    /**
     * The analyst's verdict, which the job status does not carry.
     *
     * <p>A refused request finishes the pipeline normally and is stored as COMPLETED. To the
     * person who was turned down that reads as "done", so the screen is told plainly instead.
     */
    private static boolean refused(List<ResultRow> results) {
        return results.stream()
                .filter(row -> "coding.analyze".equals(row.handlerKey()))
                .findFirst()
                .map(row -> "infeasible".equals(row.resultPort()))
                .orElse(false);
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
        return new CodingConsoleContract.Report(text(review, "reportSummary"), criteria(review));
    }

    /**
     * The criteria the review judged, in the order it judged them.
     *
     * <p>Shared with the handover record: a round the reviewer sent back is read for exactly
     * the same thing as the round it accepted - which of the agreed statements came true.
     */
    private static List<CodingConsoleContract.CriterionResult> criteria(JsonNode review) {
        List<CodingConsoleContract.CriterionResult> verdicts = new ArrayList<>();
        if (review == null) {
            return verdicts;
        }
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
        return verdicts;
    }

    private CodingConsoleContract.Technical technical(
            JobRow job, UUID jobId, List<ResultRow> results, JsonNode preview,
            List<RunnerRow> work) {
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
                diff(jobId),
                preview == null ? null : text(preview, "checkProfile"),
                null,
                null,
                runnerFailure(work));
    }

    /**
     * The runner's own account of what broke, for the reader who can act on it.
     *
     * <p>The code alone says a step failed; the detail says which file and which rule. It is
     * kept on this side because it names paths, which is exactly what the other screen must
     * not show.
     */
    private static String runnerFailure(List<RunnerRow> work) {
        return work.stream()
                .filter(row -> "FAILED".equals(row.status()))
                .findFirst()
                .map(row -> row.detail() == null || row.detail().isBlank()
                        ? row.kind() + " " + row.errorCode()
                        : row.kind() + " " + row.errorCode() + ": " + row.detail())
                .orElse(null);
    }

    /**
     * The one thing a super administrator can actually judge. A digest proves the bytes did
     * not change after review; only the diff itself says what they are, and asking someone to
     * approve code they cannot read is not an approval. The body already sits in the newest
     * read_diff execution the code stage ran, so the console reads it back rather than asking
     * the workspace again.
     */
    private String diff(UUID jobId) {
        List<String> rows = jdbc.query("""
                SELECT result_content
                FROM app.coding_tool_execution
                WHERE job_id = ? AND tool_name = 'read_diff' AND status = 'SUCCEEDED'
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, row) -> rs.getString("result_content"), jobId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        String diff = readTree(rows.get(0)).path("diff").asText(null);
        if (diff == null || diff.isBlank()) {
            return null;
        }
        return diff.length() <= 60_000
                ? diff
                : diff.substring(0, 60_000) + System.lineSeparator()
                        + "... (이하 생략 · 전체는 변경 지문으로 검증됨)";
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
                SELECT handler_key, result_port, candidate_sha, diff_digest, payload::text,
                       recorded_at
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                ORDER BY recorded_at DESC, result_id DESC
                """,
                (rs, row) -> new ResultRow(
                        rs.getString("handler_key"),
                        rs.getString("result_port"),
                        rs.getString("candidate_sha"),
                        rs.getString("diff_digest"),
                        readTree(rs.getString("payload")),
                        instant(rs, "recorded_at")),
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
            String repository,
            String requestText,
            Instant createdAt,
            Instant finishedAt) { }

    /** One queued Docker step as the runner left it. */
    record RunnerRow(String kind, String status, String errorCode, String detail) { }

    record ResultRow(
            String handlerKey,
            String resultPort,
            String candidateSha,
            String diffDigest,
            JsonNode payload,
            Instant recordedAt) { }
}
