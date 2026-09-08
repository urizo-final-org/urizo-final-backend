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

    /**
     * 권한 없는 관리자가 남기는 자료 갱신 요청. 활성화·롤백이 일어나면 자동으로 닫히므로
     * 처리 엔드포인트가 따로 없다.
     */
    ProductApiContract.ActivationRequestResponse createActivationRequest(
            UUID knowledgeBaseId,
            UUID traceId,
            org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor actor,
            ProductApiContract.CreateActivationRequestRequest request);

    ProductApiContract.ActivationRequestListResponse listOpenActivationRequests(
            UUID knowledgeBaseId, UUID traceId);
}
