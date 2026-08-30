package org.urizo.axmodulestudio.backend.coding.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

/** Evidence-only readiness projection for the fixed AI04 approval sequence. */
final class CodingApprovalReadiness {

    private CodingApprovalReadiness() { }

    static Optional<ReadyApproval> find(JdbcTemplate jdbc, UUID jobId, int pipelineAttempt) {
        String jobStatus = jdbc.queryForObject(
                "SELECT status FROM app.coding_job WHERE job_id = ?",
                String.class,
                jobId);
        if (!"WAITING_APPROVAL".equals(jobStatus)) {
            return Optional.empty();
        }

        Map<CodingHandlerContract.ApprovalStage, DecisionEvidence> decisions =
                new EnumMap<>(CodingHandlerContract.ApprovalStage.class);
        List<DecisionRow> decisionRows = jdbc.query("""
                SELECT stage, decision, subject_candidate_sha, validation_hash
                FROM app.coding_approval_decision
                WHERE job_id = ? AND pipeline_attempt = ?
                ORDER BY decided_at, approval_id
                """, (rs, row) -> new DecisionRow(
                        CodingHandlerContract.ApprovalStage.valueOf(rs.getString("stage")),
                        new DecisionEvidence(
                                CodingHandlerContract.Decision.valueOf(rs.getString("decision")),
                                rs.getString("subject_candidate_sha"),
                                rs.getString("validation_hash"))),
                jobId, pipelineAttempt);
        decisionRows.forEach(row -> decisions.put(row.stage(), row.evidence()));

        PreviewSubject preview = latestPreview(jdbc, jobId, pipelineAttempt);
        PreviewSubject pullRequest = latestPullRequest(jdbc, jobId, pipelineAttempt);
        Evidence evidence = new Evidence(
                latestPort(jdbc, jobId, pipelineAttempt, "coding.analyze", "feasible"),
                preview == null ? null : preview.candidateSha(),
                preview == null ? null : preview.validationHash(),
                pullRequest == null ? null : pullRequest.candidateSha(),
                pullRequest == null ? null : pullRequest.validationHash());
        return determine(decisions, evidence);
    }

    static Optional<ReadyApproval> determine(
            Map<CodingHandlerContract.ApprovalStage, DecisionEvidence> decisions,
            Evidence evidence) {
        if (!decisions.containsKey(CodingHandlerContract.ApprovalStage.SCOPE)) {
            return evidence.feasibleAnalysis()
                    ? Optional.of(new ReadyApproval(
                            CodingHandlerContract.ApprovalStage.SCOPE, null, null))
                    : Optional.empty();
        }
        if (!approved(decisions.get(CodingHandlerContract.ApprovalStage.SCOPE))) {
            return Optional.empty();
        }

        boolean previewReady = evidence.previewCandidateSha() != null
                && evidence.previewValidationHash() != null;
        if (!decisions.containsKey(CodingHandlerContract.ApprovalStage.CANDIDATE)) {
            return !previewReady
                    ? Optional.empty()
                    : Optional.of(new ReadyApproval(
                            CodingHandlerContract.ApprovalStage.CANDIDATE,
                            evidence.previewCandidateSha(),
                            evidence.previewValidationHash()));
        }
        if (!approvedForSubject(
                decisions.get(CodingHandlerContract.ApprovalStage.CANDIDATE), evidence)
                || !previewReady) {
            return Optional.empty();
        }

        if (!decisions.containsKey(CodingHandlerContract.ApprovalStage.GITHUB)) {
            return evidence.previewCandidateSha().equals(evidence.pullRequestCandidateSha())
                    && evidence.previewValidationHash().equals(
                            evidence.pullRequestValidationHash())
                    ? Optional.of(new ReadyApproval(
                            CodingHandlerContract.ApprovalStage.GITHUB,
                            evidence.previewCandidateSha(),
                            evidence.previewValidationHash()))
                    : Optional.empty();
        }
        if (!approvedForSubject(
                decisions.get(CodingHandlerContract.ApprovalStage.GITHUB), evidence)) {
            return Optional.empty();
        }

        if (!decisions.containsKey(CodingHandlerContract.ApprovalStage.CMS)) {
            return Optional.of(new ReadyApproval(
                    CodingHandlerContract.ApprovalStage.CMS,
                    evidence.previewCandidateSha(),
                    evidence.previewValidationHash()));
        }
        if (!approvedForSubject(
                decisions.get(CodingHandlerContract.ApprovalStage.CMS), evidence)) {
            return Optional.empty();
        }

        if (!decisions.containsKey(CodingHandlerContract.ApprovalStage.DEPLOY)) {
            return Optional.of(new ReadyApproval(
                    CodingHandlerContract.ApprovalStage.DEPLOY,
                    evidence.previewCandidateSha(),
                    evidence.previewValidationHash()));
        }
        return Optional.empty();
    }

    private static boolean approved(DecisionEvidence evidence) {
        return evidence != null
                && evidence.decision() == CodingHandlerContract.Decision.APPROVED;
    }

    private static boolean approvedForSubject(DecisionEvidence decision, Evidence evidence) {
        return approved(decision)
                && Objects.equals(decision.candidateSha(), evidence.previewCandidateSha())
                && Objects.equals(decision.validationHash(), evidence.previewValidationHash());
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

    private static PreviewSubject latestPreview(
            JdbcTemplate jdbc, UUID jobId, int pipelineAttempt) {
        List<PreviewSubject> rows = jdbc.query("""
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.preview'
                  AND result_type = 'DIFF'
                  AND result_port = 'ready'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """, (rs, row) -> new PreviewSubject(
                        rs.getString("candidate_sha"),
                        rs.getString("validation_hash")),
                jobId, pipelineAttempt);
        if (rows.size() != 1
                || rows.get(0).candidateSha() == null
                || rows.get(0).validationHash() == null) {
            return null;
        }
        return rows.get(0);
    }

    private static PreviewSubject latestPullRequest(
            JdbcTemplate jdbc, UUID jobId, int pipelineAttempt) {
        List<PreviewSubject> subjects = jdbc.query("""
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.pr_request'
                  AND result_type = 'PULL_REQUEST'
                  AND result_port = 'requested'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """, (rs, row) -> new PreviewSubject(
                        rs.getString("candidate_sha"),
                        rs.getString("validation_hash")),
                jobId, pipelineAttempt);
        if (subjects.size() != 1
                || subjects.get(0).candidateSha() == null
                || subjects.get(0).validationHash() == null) {
            return null;
        }
        return subjects.get(0);
    }

    record ReadyApproval(
            CodingHandlerContract.ApprovalStage stage,
            String candidateSha,
            String validationHash) { }

    record Evidence(
            boolean feasibleAnalysis,
            String previewCandidateSha,
            String previewValidationHash,
            String pullRequestCandidateSha,
            String pullRequestValidationHash) { }

    record DecisionEvidence(
            CodingHandlerContract.Decision decision,
            String candidateSha,
            String validationHash) { }

    private record PreviewSubject(String candidateSha, String validationHash) { }
    private record DecisionRow(
            CodingHandlerContract.ApprovalStage stage,
            DecisionEvidence evidence) { }
}
