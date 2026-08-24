package org.urizo.axmodulestudio.backend.knowledge.service;

import java.util.UUID;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

public interface ProductJobOperations {

    ProductApiContract.AgentJobResponse getJob(UUID id, UUID traceId);

    ProductApiContract.AgentJobListResponse listJobs(UUID projectId, UUID traceId);

    ProductApiContract.AgentJobResponse cancelJob(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request);

    ProductApiContract.AgentJobResponse retryJob(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request);
}
