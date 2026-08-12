package org.urizo.axmodulestudio.backend.product;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-full")
final class ProductService {

    private final ProductStore store;

    ProductService(ProductStore store) {
        this.store = store;
    }

    ProductApiContract.ProjectResponse createProject(
            UUID traceId, String key, ProductApiContract.CreateProjectRequest request) {
        return store.idempotent("CREATE_PROJECT", key, request, 201,
                ProductApiContract.ProjectResponse.class,
                () -> store.createProject(traceId, request));
    }

    ProductApiContract.ProjectListResponse listProjects(UUID traceId) {
        return new ProductApiContract.ProjectListResponse(
                version(), traceId, store.listProjects(traceId));
    }

    ProductApiContract.ProjectResponse getProject(UUID id, UUID traceId) {
        return store.getProject(id, traceId);
    }

    ProductApiContract.ConnectorResponse createConnector(
            UUID projectId,
            UUID traceId,
            String key,
            ProductApiContract.CreateConnectorRequest request) {
        return store.idempotent("CREATE_CONNECTOR", key,
                new ScopedRequest(projectId, request), 201,
                ProductApiContract.ConnectorResponse.class,
                () -> store.createConnector(projectId, traceId, request));
    }

    ProductApiContract.ConnectorListResponse listConnectors(UUID projectId, UUID traceId) {
        return new ProductApiContract.ConnectorListResponse(
                version(), traceId, store.listConnectors(projectId, traceId));
    }

    ProductApiContract.ConnectorResponse getConnector(UUID id, UUID traceId) {
        return store.getConnector(id, traceId);
    }

    ProductApiContract.ConnectorPreviewResponse previewConnector(
            UUID connectorId,
            UUID traceId,
            String key,
            ProductApiContract.ConnectorPreviewRequest request) {
        return store.idempotent("PREVIEW_CONNECTOR", key,
                new ScopedRequest(connectorId, request), 200,
                ProductApiContract.ConnectorPreviewResponse.class,
                () -> store.previewConnector(connectorId, traceId, request));
    }

    ProductApiContract.ConnectorResponse activateConnectorVersion(
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

    ProductApiContract.JobAcceptedResponse syncConnector(
            UUID connectorId,
            UUID traceId,
            String key,
            ProductApiContract.ConnectorSyncRequest request) {
        return store.idempotent("SYNC_CONNECTOR", key,
                new ScopedRequest(connectorId, request), 202,
                ProductApiContract.JobAcceptedResponse.class,
                () -> store.createConnectorSync(connectorId, traceId, request));
    }

    ProductApiContract.KnowledgeBaseResponse createKnowledgeBase(
            UUID traceId,
            String key,
            ProductApiContract.CreateKnowledgeBaseRequest request) {
        return store.idempotent("CREATE_KNOWLEDGE_BASE", key, request, 201,
                ProductApiContract.KnowledgeBaseResponse.class,
                () -> store.createKnowledgeBase(traceId, request));
    }

    ProductApiContract.KnowledgeBaseListResponse listKnowledgeBases(
            UUID projectId, UUID traceId) {
        return new ProductApiContract.KnowledgeBaseListResponse(
                version(), traceId, store.listKnowledgeBases(projectId, traceId));
    }

    ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(UUID id, UUID traceId) {
        return store.getKnowledgeBase(id, traceId);
    }

    ProductApiContract.JobAcceptedResponse startKnowledgeBuild(
            UUID knowledgeBaseId,
            UUID traceId,
            String key,
            ProductApiContract.StartKnowledgeBuildRequest request) {
        return store.idempotent("START_KNOWLEDGE_BUILD", key,
                new ScopedRequest(knowledgeBaseId, request), 202,
                ProductApiContract.JobAcceptedResponse.class,
                () -> store.createKnowledgeBuild(knowledgeBaseId, traceId, request));
    }

    ProductApiContract.KnowledgeVersionListResponse listKnowledgeVersions(
            UUID knowledgeBaseId, UUID traceId) {
        return new ProductApiContract.KnowledgeVersionListResponse(
                version(), traceId, store.listKnowledgeVersions(knowledgeBaseId, traceId));
    }

    ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(UUID id, UUID traceId) {
        return store.getKnowledgeVersion(id, traceId);
    }

    ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request) {
        return store.idempotent("ACTIVATE_KNOWLEDGE", key,
                new ScopedRequest(id, request), 200,
                ProductApiContract.KnowledgeVersionResponse.class,
                () -> store.activateKnowledgeVersion(id, traceId, request.expectedStateVersion()));
    }

    ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
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

    ProductApiContract.ChatbotResponse createChatbot(
            UUID projectId,
            UUID traceId,
            String key,
            ProductApiContract.CreateChatbotRequest request) {
        return store.idempotent("CREATE_CHATBOT", key,
                new ScopedRequest(projectId, request), 201,
                ProductApiContract.ChatbotResponse.class,
                () -> store.createChatbot(projectId, traceId, request));
    }

    ProductApiContract.ChatbotListResponse listChatbots(UUID projectId, UUID traceId) {
        return new ProductApiContract.ChatbotListResponse(
                version(), traceId, store.listChatbots(projectId, traceId));
    }

    ProductApiContract.ChatbotResponse getChatbot(UUID id, UUID traceId) {
        return store.getChatbot(id, traceId);
    }

    ProductApiContract.RagQueryResponse query(
            UUID chatbotId,
            UUID traceId,
            String key,
            ProductApiContract.RagQueryRequest request) {
        return store.idempotent("QUERY_CHATBOT", key,
                new ScopedRequest(chatbotId, request), 200,
                ProductApiContract.RagQueryResponse.class,
                () -> store.query(chatbotId, traceId, request));
    }

    ProductApiContract.AgentJobResponse getJob(UUID id, UUID traceId) {
        return store.getJob(id, traceId);
    }

    ProductApiContract.AgentJobListResponse listJobs(UUID projectId, UUID traceId) {
        return new ProductApiContract.AgentJobListResponse(
                version(), traceId, store.listJobs(projectId, traceId));
    }

    ProductApiContract.AgentJobResponse cancelJob(
            UUID id,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request) {
        return store.idempotent("CANCEL_PRODUCT_JOB", key,
                new ScopedRequest(id, request), 200,
                ProductApiContract.AgentJobResponse.class,
                () -> store.cancelJob(id, traceId, request.expectedStateVersion()));
    }

    ProductApiContract.AgentJobResponse retryJob(
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
