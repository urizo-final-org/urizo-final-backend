package org.urizo.axmodulestudio.backend.coding.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the administrator console reads. The worker contract in {@link CodingHandlerContract}
 * stays as it is: it is written for a machine that already holds the Job authority, and it
 * carries shas, digests and state versions that no screen should have to understand.
 *
 * <p>Two readers share these shapes and they are not equals. A general administrator approves
 * the plan and the preview and cannot read code; a super administrator approves the merge and
 * needs the diff. Rather than scatter that difference across the screen, everything a general
 * administrator must not see is collected into {@link Technical}, and the server leaves that
 * one field null for them. Hiding it in the browser would not be hiding it at all.
 */
public final class CodingConsoleContract {

    private CodingConsoleContract() {
    }

    /**
     * One row of the request list. Enough to decide which request to open, and deliberately no
     * more: the approval a Job waits on costs a readiness computation per row, and the screen
     * opens one request before it needs that answer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobSummary(
            UUID jobId,
            String repository,
            String requestText,
            String status,
            String currentStage,
            Instant createdAt,
            Instant finishedAt) { }

    /** The list endpoint's envelope. */
    public record JobList(
            String schemaVersion,
            List<JobSummary> items) {

        public JobList {
            items = List.copyOf(items);
        }
    }

    /**
     * One request, opened. {@code plan} feeds approval 1, {@code report} feeds approval 2,
     * and {@code technical} feeds approval 3 and is null for a general administrator.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobDetail(
            String schemaVersion,
            UUID jobId,
            String repository,
            String requestText,
            String status,
            String currentStage,
            int pipelineAttempt,
            int maxPipelineAttempts,
            Plan plan,
            Report report,
            PendingApproval pendingApproval,
            List<DecisionRecord> decisions,
            PreviewLink preview,
            Technical technical,
            Instant createdAt,
            Instant finishedAt) {

        public JobDetail {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    /**
     * Agent 1's plan in the requester's own words, and the checklist both sides agree to
     * before any code is written. Either field can be null: the model is asked for them but
     * a missing field is shown as missing rather than failing the Job.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Plan(
            String summary,
            List<String> acceptanceCriteria) {

        public Plan {
            acceptanceCriteria = acceptanceCriteria == null
                    ? List.of() : List.copyOf(acceptanceCriteria);
        }
    }

    /** Agent 3's report, judged against the checklist approval 1 agreed to. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(
            String summary,
            List<CriterionResult> criteriaResults) {

        public Report {
            criteriaResults = criteriaResults == null
                    ? List.of() : List.copyOf(criteriaResults);
        }
    }

    /** {@code met} is null when the model judged the criterion but did not say either way. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CriterionResult(
            String criterion,
            Boolean met) { }

    /**
     * The approval the Job is waiting on, with every value the decision endpoint will ask for.
     * The screen echoes these back rather than deriving them, because the identifier is a
     * deterministic hash of the stage and round and a screen must not try to recompute it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingApproval(
            UUID approvalId,
            UUID traceId,
            String nodeId,
            String stage,
            int stageRound,
            String requiredRole,
            int expectedStateVersion,
            int pipelineAttempt,
            String candidateSha,
            String validationHash) { }

    /** A decision already taken, newest last. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionRecord(
            String stage,
            String decision,
            String actorRole,
            String feedback,
            Instant decidedAt) { }

    /** Where a general administrator looks instead of reading the diff. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewLink(
            boolean ready,
            String url) { }

    /**
     * Super administrator only. The server omits this whole object for anyone else, so a
     * general administrator's response never carries a path, a sha, or a diff at all.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Technical(
            String baseSha,
            String candidateSha,
            String diffDigest,
            List<String> changedPaths,
            String diff,
            String checkProfile,
            String pullRequestUrl,
            BaseShaFreshness baseShaFreshness) {

        public Technical {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }

    /**
     * Approval 3 merges work that started from a commit that may no longer be the head of
     * dev. {@code stale} true means the branch moved underneath the Job.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BaseShaFreshness(
            boolean stale,
            String currentDevSha) { }

    /** The request a general administrator actually types. Everything else the server fills in. */
    public record CreateJobRequest(
            String repository,
            String requestText) { }
}
