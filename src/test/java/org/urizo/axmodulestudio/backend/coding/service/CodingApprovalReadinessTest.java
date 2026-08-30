package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

class CodingApprovalReadinessTest {

    private static final String CANDIDATE =
            "sha1:1111111111111111111111111111111111111111";
    private static final String VALIDATION =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";

    @Test
    void exposesOnlyTheEvidenceReadyApprovalStage() {
        Map<CodingHandlerContract.ApprovalStage, CodingApprovalReadiness.DecisionEvidence> decisions =
                new EnumMap<>(CodingHandlerContract.ApprovalStage.class);
        CodingApprovalReadiness.Evidence completeEvidence =
                new CodingApprovalReadiness.Evidence(
                        true, CANDIDATE, VALIDATION, CANDIDATE, VALIDATION);

        assertThat(CodingApprovalReadiness.determine(
                decisions,
                new CodingApprovalReadiness.Evidence(false, null, null, null, null))).isEmpty();
        assertThat(CodingApprovalReadiness.determine(decisions, completeEvidence))
                .get().extracting(CodingApprovalReadiness.ReadyApproval::stage)
                .isEqualTo(CodingHandlerContract.ApprovalStage.SCOPE);

        decisions.put(
                CodingHandlerContract.ApprovalStage.SCOPE,
                approved(null, null));
        assertThat(CodingApprovalReadiness.determine(decisions, completeEvidence))
                .get().satisfies(ready -> {
                    assertThat(ready.stage())
                            .isEqualTo(CodingHandlerContract.ApprovalStage.CANDIDATE);
                    assertThat(ready.candidateSha()).isEqualTo(CANDIDATE);
                    assertThat(ready.validationHash()).isEqualTo(VALIDATION);
                });

        decisions.put(
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                approved(CANDIDATE, VALIDATION));
        assertThat(CodingApprovalReadiness.determine(decisions, completeEvidence))
                .get().extracting(CodingApprovalReadiness.ReadyApproval::stage)
                .isEqualTo(CodingHandlerContract.ApprovalStage.GITHUB);

        decisions.put(
                CodingHandlerContract.ApprovalStage.GITHUB,
                approved(CANDIDATE, VALIDATION));
        assertThat(CodingApprovalReadiness.determine(decisions, completeEvidence))
                .get().extracting(CodingApprovalReadiness.ReadyApproval::stage)
                .isEqualTo(CodingHandlerContract.ApprovalStage.CMS);

        decisions.put(
                CodingHandlerContract.ApprovalStage.CMS,
                approved(CANDIDATE, VALIDATION));
        assertThat(CodingApprovalReadiness.determine(decisions, completeEvidence))
                .get().extracting(CodingApprovalReadiness.ReadyApproval::stage)
                .isEqualTo(CodingHandlerContract.ApprovalStage.DEPLOY);
    }

    @Test
    void rejectsOutOfOrderOrStalePostPreviewEvidence() {
        Map<CodingHandlerContract.ApprovalStage, CodingApprovalReadiness.DecisionEvidence> decisions =
                new EnumMap<>(CodingHandlerContract.ApprovalStage.class);
        decisions.put(
                CodingHandlerContract.ApprovalStage.SCOPE,
                approved(null, null));
        decisions.put(
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                approved(CANDIDATE, VALIDATION));

        assertThat(CodingApprovalReadiness.determine(
                decisions,
                new CodingApprovalReadiness.Evidence(
                        true, CANDIDATE, VALIDATION, null, null)))
                .isEmpty();
        assertThat(CodingApprovalReadiness.determine(
                decisions,
                new CodingApprovalReadiness.Evidence(
                        true,
                        CANDIDATE,
                        VALIDATION,
                        "sha1:3333333333333333333333333333333333333333",
                        VALIDATION)))
                .isEmpty();

        assertThat(CodingApprovalReadiness.determine(
                decisions,
                new CodingApprovalReadiness.Evidence(
                        true,
                        CANDIDATE,
                        VALIDATION,
                        CANDIDATE,
                        "sha256:5555555555555555555555555555555555555555555555555555555555555555")))
                .isEmpty();

        decisions.put(
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                new CodingApprovalReadiness.DecisionEvidence(
                        CodingHandlerContract.Decision.REJECTED, CANDIDATE, VALIDATION));
        assertThat(CodingApprovalReadiness.determine(
                decisions,
                new CodingApprovalReadiness.Evidence(
                        true, CANDIDATE, VALIDATION, CANDIDATE, VALIDATION)))
                .isEmpty();
    }

    @Test
    void rejectsApprovedPredecessorsBoundToAStalePreviewSubject() {
        String laterCandidate = "sha1:3333333333333333333333333333333333333333";
        String laterValidation =
                "sha256:4444444444444444444444444444444444444444444444444444444444444444";
        CodingApprovalReadiness.Evidence laterEvidence =
                new CodingApprovalReadiness.Evidence(
                        true, laterCandidate, laterValidation, laterCandidate, laterValidation);
        Map<CodingHandlerContract.ApprovalStage, CodingApprovalReadiness.DecisionEvidence> decisions =
                new EnumMap<>(CodingHandlerContract.ApprovalStage.class);
        decisions.put(CodingHandlerContract.ApprovalStage.SCOPE, approved(null, null));
        decisions.put(
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                approved(CANDIDATE, VALIDATION));

        assertThat(CodingApprovalReadiness.determine(decisions, laterEvidence)).isEmpty();

        decisions.put(
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                approved(laterCandidate, laterValidation));
        decisions.put(
                CodingHandlerContract.ApprovalStage.GITHUB,
                approved(CANDIDATE, VALIDATION));
        assertThat(CodingApprovalReadiness.determine(decisions, laterEvidence)).isEmpty();

        decisions.put(
                CodingHandlerContract.ApprovalStage.GITHUB,
                approved(laterCandidate, laterValidation));
        decisions.put(
                CodingHandlerContract.ApprovalStage.CMS,
                approved(CANDIDATE, VALIDATION));
        assertThat(CodingApprovalReadiness.determine(decisions, laterEvidence)).isEmpty();
    }

    private static CodingApprovalReadiness.DecisionEvidence approved(
            String candidateSha, String validationHash) {
        return new CodingApprovalReadiness.DecisionEvidence(
                CodingHandlerContract.Decision.APPROVED, candidateSha, validationHash);
    }
}
