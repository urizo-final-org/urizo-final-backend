package org.urizo.axmodulestudio.backend.coding.worker;

import static org.assertj.core.api.Assertions.assertThat;

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
}
