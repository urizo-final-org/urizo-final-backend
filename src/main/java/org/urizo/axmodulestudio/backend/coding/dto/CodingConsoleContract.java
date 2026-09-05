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
            Instant finishedAt,
            String failureCode,
            /**
             * The analyst judged the request outside what it may change. The pipeline then
             * ends normally, so the job status is COMPLETED - which reads as "done" to the
             * person who was actually turned down. The screen needs this to say otherwise.
             */
            boolean refused,
            /**
             * The model used up its rework rounds and the request was handed to a person. That
             * also ends the pipeline normally and also reads as "done", for the same reason and
             * with the same consequence: nobody picks up work they were told had finished.
             */
            boolean handedOver) { }

    /** The list endpoint's envelope. */
    public record JobList(
            String schemaVersion,
            List<JobSummary> items) {

        public JobList {
            items = List.copyOf(items);
        }
    }

    /**
     * Whether the runner is answering. {@code lastSeenAt} is null when the server has not
     * heard from it since starting — the screen must warn on that too, because "no signal
     * yet" and "off" look the same to the person whose request would silently wait.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunnerStatus(
            String schemaVersion,
            boolean alive,
            Instant lastSeenAt) { }

    /**
     * One thing that happened while the reader was not looking.
     *
     * <p>{@code actorName} is the person, not the role: "누가 승인했나" is the question an
     * approval ledger has to answer, and a demo with one account per role still reads better
     * with a name than with a role word repeated on every line. It is absent for a waiting
     * approval, which nobody has decided yet.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(
            String kind,
            UUID jobId,
            String requestText,
            String stage,
            String decision,
            String actorName,
            String actorRole,
            Instant occurredAt) { }

    /** What the bell counts and the screen lists, newest first. */
    public record NotificationList(
            String schemaVersion,
            List<Notification> items) {

        public NotificationList {
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
            Handover handover,
            PreviewLink preview,
            Technical technical,
            Instant createdAt,
            Instant finishedAt,
            boolean refused) {

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

    /**
     * What a person inherits when the model ran out of tries.
     *
     * <p>The review may send a candidate back for rework a fixed number of times. On the last
     * refusal the Job ends normally rather than as an execution error, precisely so that what
     * it made and what was wrong with it survive to be read. Absent on every Job that did not
     * end that way.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Handover(
            int rounds,
            List<Attempt> attempts) {

        public Handover {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    /**
     * One pass the model made at the request, and what the review made of it.
     *
     * <p>{@code summary} is the reviewer's own words, which the contract already requires to be
     * readable by someone who cannot read code. That is why this sits outside {@link Technical}:
     * whoever decides what to do with an abandoned request is not necessarily the person who
     * can read the diff, and "the AI gave up" is not a fact to keep from them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attempt(
            int round,
            boolean accepted,
            String summary,
            List<CriterionResult> criteriaResults,
            Instant recordedAt) {

        public Attempt {
            criteriaResults = criteriaResults == null ? List.of() : List.copyOf(criteriaResults);
        }
    }

    /**
     * Where a general administrator looks instead of reading the diff.
     *
     * <p>{@code ready} used to mean "the preview stage recorded a result", which is not the
     * same as "the preview is up". Raising it is Docker work the runner does afterwards, and
     * when that failed the screen still offered the link - to the previous request's preview,
     * which looks perfectly fine and is not what anyone is approving.
     *
     * <p>{@code blocked} is the reason there is nothing to open, in the requester's own words
     * and without a path or a symbol in it: this is the screen a general administrator reads.
     * Both null means the preview is still being raised.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewLink(
            boolean ready,
            String url,
            String blocked) { }

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
            BaseShaFreshness baseShaFreshness,
            /**
             * Why the build, the checks or the preview did not finish, as the runner reported
             * it. It names files and compiler codes, so it stays on this side of the fence.
             */
            String runnerFailure) {

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

    /**
     * The request a general administrator actually types. Everything else the server fills in.
     *
     * <p>{@code repository} is no longer asked of the person - a sentence like "가입일도 보이게
     * 해줘" has no answer they could know, since it needs both sides. Left empty, the server
     * classifies. The field stays because the screen itself sends it on the second leg of a
     * split, where the answer is already decided.
     */
    public record CreateJobRequest(
            String repository,
            String requestText) { }

    /**
     * What submitting a request comes back with. {@code created} is always the Job that was
     * started; {@code split} is present only when the sentence needed both sides.
     *
     * <p>A both-sides request is not refused and not confirmed - the person cannot judge the
     * split, so asking them to would be one more unanswerable question. The data half simply
     * starts, and {@code split} tells the screen what is happening in the requester's own
     * words: which part is running now, and which part follows when it is done. The plan
     * approval stays the place where a person says no.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateJobOutcome(
            String schemaVersion,
            CodingHandlerContract.CreateCodingJobResponse created,
            SplitPlan split) { }

    /**
     * The two parts of a both-sides request, phrased by the classifier from the requester's
     * own sentence - never in system words. The data part runs first: a screen drawing a value
     * the server does not send yet reads as a failure to the person previewing it.
     */
    public record SplitPlan(
            String firstText,
            String secondText) { }
}
