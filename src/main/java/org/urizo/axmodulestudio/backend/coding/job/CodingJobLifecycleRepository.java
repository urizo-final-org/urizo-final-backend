package org.urizo.axmodulestudio.backend.coding.job;

import java.util.UUID;

interface CodingJobLifecycleRepository {

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
