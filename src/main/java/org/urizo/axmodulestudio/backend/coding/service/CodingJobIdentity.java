package org.urizo.axmodulestudio.backend.coding.service;

import java.util.Locale;
import java.util.UUID;

final class CodingJobIdentity {

    private CodingJobIdentity() { }

    static WorkIdentity workIdentity(UUID jobId) {
        String suffix = jobId.toString().replace("-", "");
        return new WorkIdentity(
                "SYSTEM-LLMOPS-" + suffix.toUpperCase(Locale.ROOT),
                "system-llmops-" + suffix);
    }

    record WorkIdentity(String systemWorkId, String workSlug) { }
}
