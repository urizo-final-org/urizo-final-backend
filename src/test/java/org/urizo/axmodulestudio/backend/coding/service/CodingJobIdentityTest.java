package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CodingJobIdentityTest {

    @Test
    void derivesStableServerOwnedWorkIdentityFromJobId() {
        UUID jobId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

        CodingJobIdentity.WorkIdentity identity = CodingJobIdentity.workIdentity(jobId);

        assertThat(identity.systemWorkId())
                .isEqualTo("SYSTEM-LLMOPS-AAAAAAAABBBB4CCC8DDDEEEEEEEEEEEE");
        assertThat(identity.workSlug())
                .isEqualTo("system-llmops-aaaaaaaabbbb4ccc8dddeeeeeeeeeeee");
        assertThat(CodingJobIdentity.workIdentity(jobId)).isEqualTo(identity);
    }
}
