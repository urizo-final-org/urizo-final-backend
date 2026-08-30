package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;

class CodingJobLifecycleRequestDigesterTest {

    private static final UUID JOB_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    private final CodingJobLifecycleRequestDigester digester =
            new CodingJobLifecycleRequestDigester(new ObjectMapper());

    @Test
    void bindsApprovalAndRejectionToDifferentIdempotencyDigests() {
        byte[] approved = digester.transition(
                JOB_ID,
                TRACE_ID,
                transition(CodingJobLifecycleContract.Status.RUNNING));
        byte[] approvedReplay = digester.transition(
                JOB_ID,
                TRACE_ID,
                transition(CodingJobLifecycleContract.Status.RUNNING));
        byte[] rejected = digester.transition(
                JOB_ID,
                TRACE_ID,
                transition(CodingJobLifecycleContract.Status.CANCELLED));

        assertThat(approvedReplay).containsExactly(approved);
        assertThat(rejected).isNotEqualTo(approved);
    }

    private static CodingJobLifecycleContract.TransitionRequest transition(
            CodingJobLifecycleContract.Status targetStatus) {
        return new CodingJobLifecycleContract.TransitionRequest(
                "1.0", 3, targetStatus, null);
    }
}
