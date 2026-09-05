package org.urizo.axmodulestudio.backend.knowledge.service;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.repository.ProductStore;

@Service
@Profile("local-full")
public final class ProductService implements
        ProjectOperations,
        ConnectorOperations,
        KnowledgeOperations,
        RagOperations,
        ProductJobOperations {

    private final ProductStore store;

    ProductService(ProductStore store) {
        this.store = store;
    }

    public ProductApiContract.ProjectResponse createProject(
            UUID traceId, String key, ProductApiContract.CreateProjectRequest request) {
        return store.idempotent("CREATE_PROJECT", key, request, 201,
                ProductApiContract.ProjectResponse.class,
                () -> store.createProject(traceId, request));
    }

    public ProductApiContract.ProjectListResponse listProjects(UUID traceId) {
        return new ProductApiContract.ProjectListResponse(
                version(), traceId, store.listProjects(traceId));
    }

    public ProductApiContract.ProjectResponse getProject(UUID id, UUID traceId) {
        return store.getProject(id, traceId);
    }

    public ProductApiContract.ConnectorResponse createConnector(
            UUID projectId,
            UUID traceId,
            String key,
            ProductApiContract.CreateConnectorRequest request) {
        return store.idempotent("CREATE_CONNECTOR", key,
                new ScopedRequest(projectId, request), 201,
                ProductApiContract.ConnectorResponse.class,
                () -> store.createConnector(projectId, traceId, request));
    }

    public ProductApiContract.ConnectorListResponse listConnectors(UUID projectId, UUID traceId) {
        return new ProductApiContract.ConnectorListResponse(
                version(), traceId, store.listConnectors(projectId, traceId));
    }

    public ProductApiContract.ConnectorResponse getConnector(UUID id, UUID traceId) {
        return store.getConnector(id, traceId);
    }

    public ProductApiContract.ConnectorPreviewResponse previewConnector(
            UUID connectorId,
            UUID traceId,
            String key,
            ProductApiContract.ConnectorPreviewRequest request) {
        return store.idempotent("PREVIEW_CONNECTOR", key,
                new ScopedRequest(connectorId, request), 200,
                ProductApiContract.ConnectorPreviewResponse.class,
                () -> store.previewConnector(connectorId, traceId, request));
    }

    public ProductApiContract.ConnectorResponse activateConnectorVersion(
            UUID connectorId,
            UUID versionId,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request) {
        return store.idempotent("ACTIVATE_CONNECTOR", key,
                new DoubleScopedRequest(connectorId, versionId, request), 200,
                ProductApiContract.ConnectorResponse.class,
                () -> store.activateConnectorVersion(connectorId, versionId, traceId));
    }

    public ProductApiContract.JobAcceptedResponse syncConnector(
            UUID connectorId,
            UUID traceId,
            String key,
            ProductApiContract.ConnectorSyncRequest request) {
        return store.idempotent("SYNC_CONNECTOR", key,
                new ScopedRequest(connectorId, request), 202,
                ProductApiContract.JobAcceptedResponse.class,
                () -> store.createConnectorSync(connectorId, traceId, request));
    }

    public ProductApiContract.KnowledgeBaseResponse createKnowledgeBase(
            UUID traceId,
            String key,
            ProductApiContract.CreateKnowledgeBaseRequest request) {
        return store.idempotent("CREATE_KNOWLEDGE_BASE", key, request, 201,
                ProductApiContract.KnowledgeBaseResponse.class,
                () -> store.createKnowledgeBase(traceId, request));
    }

    public ProductApiContract.KnowledgeBaseListResponse listKnowledgeBases(
            UUID projectId, UUID traceId) {
        return new ProductApiContract.KnowledgeBaseListResponse(
                version(), traceId, store.listKnowledgeBases(projectId, traceId));
    }

    public ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(UUID id, UUID traceId) {
        return store.getKnowledgeBase(id, traceId);
    }

    public ProductApiContract.JobAcceptedResponse startKnowledgeBuild(
            UUID knowledgeBaseId,
            UUID traceId,
            String key,
            ProductApiContract.StartKnowledgeBuildRequest request) {
        return store.idempotent("START_KNOWLEDGE_BUILD", key,
                new ScopedRequest(knowledgeBaseId, request), 202,
                ProductApiContract.JobAcceptedResponse.class,
                () -> store.createKnowledgeBuild(knowledgeBaseId, traceId, request));
    }

    public ProductApiContract.KnowledgeVersionListResponse listKnowledgeVersions(
            UUID knowledgeBaseId, UUID traceId) {
        return new ProductApiContract.KnowledgeVersionListResponse(
                version(), traceId, store.listKnowledgeVersions(knowledgeBaseId, traceId));
    }

    public ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(UUID id, UUID traceId) {
        return store.getKnowledgeVersion(id, traceId);
    }

    public ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request) {
        return store.idempotent("ACTIVATE_KNOWLEDGE", key,
                new ScopedRequest(id, request), 200,
                ProductApiContract.KnowledgeVersionResponse.class,
                () -> store.activateKnowledgeVersion(id, traceId, request.expectedStateVersion()));
    }

    public ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            UUID knowledgeBaseId,
            UUID traceId,
            String key,
            ProductApiContract.RollbackKnowledgeRequest request) {
        return store.idempotent("ROLLBACK_KNOWLEDGE", key,
                new ScopedRequest(knowledgeBaseId, request), 200,
                ProductApiContract.KnowledgeVersionResponse.class,
                () -> store.rollbackKnowledgeVersion(
                        knowledgeBaseId, request.targetKnowledgeVersionId(), traceId));
    }

    public ProductApiContract.ChatbotResponse createChatbot(
            UUID projectId,
            UUID traceId,
            String key,
            ProductApiContract.CreateChatbotRequest request) {
        return store.idempotent("CREATE_CHATBOT", key,
                new ScopedRequest(projectId, request), 201,
                ProductApiContract.ChatbotResponse.class,
                () -> store.createChatbot(projectId, traceId, request));
    }

    public ProductApiContract.ChatbotListResponse listChatbots(UUID projectId, UUID traceId) {
        return new ProductApiContract.ChatbotListResponse(
                version(), traceId, store.listChatbots(projectId, traceId));
    }

    public ProductApiContract.ChatbotResponse getChatbot(UUID id, UUID traceId) {
        return store.getChatbot(id, traceId);
    }

    public ProductApiContract.RagQueryResponse query(
            UUID chatbotId,
            UUID traceId,
            String key,
            ProductApiContract.RagQueryRequest request) {
        return store.idempotent("QUERY_CHATBOT", key,
                new ScopedRequest(chatbotId, request), 200,
                ProductApiContract.RagQueryResponse.class,
                () -> store.query(chatbotId, traceId, request, null));
    }

    /**
     * 공개 경로는 읽기 전용 조회라 멱등 보장이 필요 없다. 관리자 경로처럼
     * {@code store.idempotent}로 감싸면 (a) 익명 브라우저에 Idempotency-Key 헤더를
     * 요구해야 하고 (b) 공개 호출마다 product_idempotency_command 행이 무한히 쌓인다.
     */
    public ProductApiContract.RagQueryResponse publicQuery(
            UUID chatbotId,
            UUID traceId,
            ProductApiContract.RagQueryRequest request,
            List<String> category) {
        return store.query(chatbotId, traceId, request, category);
    }

    public ProductApiContract.AgentJobResponse getJob(UUID id, UUID traceId) {
        return store.getJob(id, traceId);
    }

    public ProductApiContract.AgentJobListResponse listJobs(UUID projectId, UUID traceId) {
        return new ProductApiContract.AgentJobListResponse(
                version(), traceId, store.listJobs(projectId, traceId));
    }

    public ProductApiContract.AgentJobResponse cancelJob(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request) {
        return store.idempotent("CANCEL_PRODUCT_JOB", key,
                new ScopedRequest(id, request), 200,
                ProductApiContract.AgentJobResponse.class,
                () -> store.cancelJob(id, traceId, request.expectedStateVersion()));
    }

    public ProductApiContract.AgentJobResponse retryJob(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request) {
        return store.idempotent("RETRY_PRODUCT_JOB", key,
                new ScopedRequest(id, request), 200,
                ProductApiContract.AgentJobResponse.class,
                () -> store.retryJob(id, traceId, request.expectedStateVersion()));
    }

    private static String version() {
        return ProductApiContract.SCHEMA_VERSION;
    }

    private record ScopedRequest(UUID resourceId, Object request) { }
    private record DoubleScopedRequest(UUID resourceId, UUID childId, Object request) { }
}
