package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
                CodingWorkerService.claimSource("PENDING", 4, 0, null);
        CodingWorkerService.ClaimSource claimed =
                CodingWorkerService.claimSource("RUNNING", 5, 1, leaseId);
        CodingWorkerService.ClaimSource renewedExpiredClaim =
                CodingWorkerService.claimSource("RUNNING", 5, 1, leaseId);
        CodingWorkerService.ClaimSource retry =
                CodingWorkerService.claimSource("PENDING", 6, 1, null);

        assertThat(pending).isEqualTo(new CodingWorkerService.ClaimSource(4, 1));
        assertThat(claimed).isEqualTo(pending);
        assertThat(renewedExpiredClaim).isEqualTo(claimed);
        assertThat(CodingWorkerService.claimEventId(jobId, claimed.stateVersion()))
                .isEqualTo(CodingWorkerService.claimEventId(jobId, pending.stateVersion()));
        assertThat(CodingWorkerService.claimIdempotencyKey(jobId, claimed.stateVersion()))
                .isEqualTo(CodingWorkerService.claimIdempotencyKey(jobId, pending.stateVersion()));
        assertThat(retry).isEqualTo(new CodingWorkerService.ClaimSource(6, 2));
        assertThatThrownBy(() -> CodingWorkerService.claimSource("RUNNING", 1, 0, leaseId))
                .isInstanceOf(CodingWorkerException.class);
        assertThatThrownBy(() -> CodingWorkerService.claimSource("COMPLETED", 7, 1, null))
                .isInstanceOf(CodingWorkerException.class);
    }
}
