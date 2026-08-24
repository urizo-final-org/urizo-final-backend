package org.urizo.axmodulestudio.backend.knowledge.service;

import java.util.UUID;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

public interface KnowledgeOperations {

    ProductApiContract.KnowledgeBaseResponse createKnowledgeBase(
            UUID traceId,
            String key,
            ProductApiContract.CreateKnowledgeBaseRequest request);

    ProductApiContract.KnowledgeBaseListResponse listKnowledgeBases(
            UUID projectId, UUID traceId);

    ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(UUID id, UUID traceId);

    ProductApiContract.JobAcceptedResponse startKnowledgeBuild(
            UUID knowledgeBaseId,
            UUID traceId,
            String key,
            ProductApiContract.StartKnowledgeBuildRequest request);

    ProductApiContract.KnowledgeVersionListResponse listKnowledgeVersions(
            UUID knowledgeBaseId, UUID traceId);

    ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(UUID id, UUID traceId);

    ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request);

    ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            UUID knowledgeBaseId,
            UUID traceId,
            String key,
            ProductApiContract.RollbackKnowledgeRequest request);
}
