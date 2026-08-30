package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingWorkerContract;

class CodingWorkerLeaseTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void boundsEveryLeaseAtTheAuthoritativeJobExpiry() {
        Instant jobExpiry = NOW.plusSeconds(5);

        assertThat(CodingWorkerService.boundedLeaseExpiry(NOW, jobExpiry))
                .isEqualTo(jobExpiry);
        assertThat(CodingWorkerService.boundedLeaseExpiry(NOW, NOW.plusSeconds(60)))
                .isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void rejectsAnOtherwiseLiveLeaseAfterTheJobExpires() {
        UUID leaseId = UUID.randomUUID();

        assertThat(CodingWorkerService.leaseIsCurrent(
                "RUNNING", leaseId, NOW.plusSeconds(20), NOW, leaseId, NOW))
                .isFalse();
        assertThat(CodingWorkerService.leaseIsCurrent(
                "RUNNING", leaseId, NOW.plusSeconds(20), NOW.plusSeconds(60), leaseId, NOW))
                .isTrue();
    }

    @Test
    void reconstructsTheOriginalClaimForDuplicateDeliveryAndAdvancesOnlyAfterRetry() {
        UUID jobId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID leaseId = UUID.fromString("99999999-9999-4999-8999-999999999999");

        CodingWorkerService.ClaimSource pending =
                CodingWorkerService.claimSource("PENDING", 4, 0, null, false);
        CodingWorkerService.ClaimSource claimed =
                CodingWorkerService.claimSource("RUNNING", 5, 1, leaseId, false);
        CodingWorkerService.ClaimSource renewedExpiredClaim =
                CodingWorkerService.claimSource("RUNNING", 5, 1, leaseId, false);
        CodingWorkerService.ClaimSource approvalResume =
                CodingWorkerService.claimSource("RUNNING", 6, 1, null, true);
        CodingWorkerService.ClaimSource retry =
                CodingWorkerService.claimSource("PENDING", 6, 1, null, false);

        assertThat(pending).isEqualTo(new CodingWorkerService.ClaimSource(4, 1));
        assertThat(claimed).isEqualTo(pending);
        assertThat(renewedExpiredClaim).isEqualTo(claimed);
        assertThat(CodingWorkerService.claimEventId(jobId, claimed.stateVersion()))
                .isEqualTo(CodingWorkerService.claimEventId(jobId, pending.stateVersion()));
        assertThat(CodingWorkerService.claimIdempotencyKey(jobId, claimed.stateVersion()))
                .isEqualTo(CodingWorkerService.claimIdempotencyKey(jobId, pending.stateVersion()));
        assertThat(approvalResume).isEqualTo(new CodingWorkerService.ClaimSource(6, 1));
        assertThat(retry).isEqualTo(new CodingWorkerService.ClaimSource(6, 2));
        assertThatThrownBy(() ->
                CodingWorkerService.claimSource("RUNNING", 1, 0, leaseId, false))
                .isInstanceOf(CodingWorkerException.class);
        assertThatThrownBy(() ->
                CodingWorkerService.claimSource("COMPLETED", 7, 1, null, false))
                .isInstanceOf(CodingWorkerException.class);
    }

    @Test
    void preservesTheAttemptForApprovalResumeEvenAfterTheTechnicalRetryBudgetIsExhausted() {
        assertThat(CodingWorkerService.approvalResume("RUNNING", 3, null, true)).isTrue();
        assertThat(CodingWorkerService.approvalResume("PENDING", 2, null, true)).isFalse();
        assertThat(CodingWorkerService.approvalResume("RUNNING", 2, null, false)).isFalse();
        assertThat(CodingWorkerService.workerAttemptForClaim("RUNNING", 3, 3, null, true))
                .isEqualTo(3);
        assertThatThrownBy(() ->
                CodingWorkerService.workerAttemptForClaim("RUNNING", 4, 3, null, true))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessage("Coding job exhausted its worker attempts.");
        assertThat(CodingWorkerService.workerAttemptForClaim("PENDING", 2, 3, null, false))
                .isEqualTo(3);
        assertThatThrownBy(() ->
                CodingWorkerService.workerAttemptForClaim("PENDING", 3, 3, null, false))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessage("Coding job exhausted its worker attempts.");
    }

    @Test
    void treatsRunningWithoutApprovalTransitionProvenanceAsATechnicalRetry() {
        assertThat(CodingWorkerService.claimSource("RUNNING", 6, 1, null, false))
                .isEqualTo(new CodingWorkerService.ClaimSource(6, 2));
        assertThat(CodingWorkerService.workerAttemptForClaim("RUNNING", 2, 3, null, false))
                .isEqualTo(3);
        assertThatThrownBy(() ->
                CodingWorkerService.workerAttemptForClaim("RUNNING", 3, 3, null, false))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessage("Coding job exhausted its worker attempts.");
    }

    @Test
    void bindsEveryFreshClaimIdentityFieldBeforeLeaseMutation() {
        UUID jobId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID traceId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        CodingWorkerService.ClaimSource source = new CodingWorkerService.ClaimSource(6, 3);
        CodingWorkerContract.ClaimRequest exact = new CodingWorkerContract.ClaimRequest(
                "1.0",
                CodingWorkerService.claimEventId(jobId, source.stateVersion()),
                jobId,
                traceId,
                CodingWorkerService.claimIdempotencyKey(jobId, source.stateVersion()),
                source.attempt(),
                source.stateVersion());

        CodingWorkerService.requireClaimSourceBinding(exact, source, source.attempt());

        assertClaimSourceMismatch(new CodingWorkerContract.ClaimRequest(
                exact.schemaVersion(), UUID.randomUUID(), exact.jobId(), exact.traceId(),
                exact.idempotencyKey(), exact.attempt(), exact.expectedStateVersion()), source);
        assertClaimSourceMismatch(new CodingWorkerContract.ClaimRequest(
                exact.schemaVersion(), exact.eventId(), exact.jobId(), exact.traceId(),
                "coding-job:mismatch:v6", exact.attempt(), exact.expectedStateVersion()), source);
        assertClaimSourceMismatch(new CodingWorkerContract.ClaimRequest(
                exact.schemaVersion(), exact.eventId(), exact.jobId(), exact.traceId(),
                exact.idempotencyKey(), exact.attempt() - 1, exact.expectedStateVersion()), source);
    }

    @Test
    void renewsEveryBoundStoredClaimWithinBudgetAndPreservesItsResumeFlag() {
        UUID jobId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID traceId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID leaseId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        CodingWorkerContract.ClaimRequest maxAttempt = new CodingWorkerContract.ClaimRequest(
                "1.0", UUID.randomUUID(), jobId, traceId,
                "coding-job:approval:v6", 3, 6);
        CodingWorkerContract.ClaimResponse approval = new CodingWorkerContract.ClaimResponse(
                "1.0", jobId, traceId, UUID.randomUUID(), leaseId,
                NOW.minusSeconds(1), 7, true, null);
        CodingWorkerContract.ClaimResponse technical = new CodingWorkerContract.ClaimResponse(
                "1.0", jobId, traceId, approval.profileVersionId(), leaseId,
                NOW.minusSeconds(1), 7, false, null);
        CodingWorkerService.ClaimSource maxSource = new CodingWorkerService.ClaimSource(6, 3);
        CodingWorkerContract.ClaimRequest initialAttempt = new CodingWorkerContract.ClaimRequest(
                "1.0", UUID.randomUUID(), jobId, traceId,
                "coding-job:initial:v1", 1, 1);
        CodingWorkerContract.ClaimResponse initial = new CodingWorkerContract.ClaimResponse(
                "1.0", jobId, traceId, approval.profileVersionId(), leaseId,
                NOW.minusSeconds(1), 2, false, null);

        assertThat(CodingWorkerService.claimReplayMatches(maxAttempt, approval, maxSource)).isTrue();
        assertThat(CodingWorkerService.claimReplayMatches(maxAttempt, technical, maxSource)).isTrue();
        assertThat(CodingWorkerService.claimReplayMatches(
                initialAttempt, initial, new CodingWorkerService.ClaimSource(1, 1))).isTrue();
        assertThat(CodingWorkerService.claimReplayMatches(
                maxAttempt, approval, new CodingWorkerService.ClaimSource(5, 3))).isFalse();
        assertThat(CodingWorkerService.renewalAttemptAllowed(3, 3)).isTrue();
        assertThat(CodingWorkerService.renewalAttemptAllowed(1, 3)).isTrue();
        assertThat(CodingWorkerService.renewalAttemptAllowed(4, 3)).isFalse();
        assertThat(CodingWorkerService.renewalAttemptAllowed(0, 3)).isFalse();
        assertThat(CodingWorkerService.renewedClaimResponse(approval, NOW).resume()).isTrue();
        assertThat(CodingWorkerService.renewedClaimResponse(technical, NOW).resume()).isFalse();
        assertThat(CodingWorkerService.renewedClaimResponse(initial, NOW).resume()).isFalse();
    }

    private static void assertClaimSourceMismatch(
            CodingWorkerContract.ClaimRequest request,
            CodingWorkerService.ClaimSource source) {
        assertThatThrownBy(() ->
                CodingWorkerService.requireClaimSourceBinding(request, source, source.attempt()))
                .isInstanceOfSatisfying(CodingWorkerException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("JOB_STATE_VERSION_CONFLICT");
                    assertThat(failure.getMessage())
                            .isEqualTo("Coding job claim does not match the authoritative event source.");
                });
    }
}
