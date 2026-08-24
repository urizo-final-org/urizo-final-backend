package org.urizo.axmodulestudio.backend.coding.repository;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import java.util.UUID;

public interface CodingJobLifecycleRepository {

    CodingJobLifecycleContract.JobResponse create(
            UUID traceId,
            String idempotencyKey,
            byte[] requestDigest,
            CodingJobLifecycleContract.CreateRequest request);

    CodingJobLifecycleContract.JobResponse find(UUID jobId, UUID traceId);

    CodingJobLifecycleContract.JobResponse transition(
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            byte[] requestDigest,
            CodingJobLifecycleContract.TransitionRequest request);
}
