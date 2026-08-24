package org.urizo.axmodulestudio.backend.knowledge.repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

/**
 * Compatibility facade for the Stage 3/4 product workflow.
 *
 * <p>Public product behavior remains centralized here while persistence ownership is delegated to
 * feature-local stores. New CMS slices can add their own package without editing this facade.
 */
@Repository
@Profile("local-full")
public class ProductStore {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final ProjectStore projects;
    private final ConnectorStore connectors;
    private final KnowledgeStore knowledge;
    private final RagStore rag;
    private final ProductJobStore jobs;

    ProductStore(
            JdbcTemplate productJdbcTemplate,
            TransactionTemplate productTransactionTemplate,
            ObjectMapper objectMapper,
            ProjectStore projects,
            ConnectorStore connectors,
            KnowledgeStore knowledge,
            RagStore rag,
            ProductJobStore jobs) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.connectors = connectors;
        this.knowledge = knowledge;
        this.rag = rag;
        this.jobs = jobs;
    }

    public <T> T idempotent(
            String operation,
            String idempotencyKey,
            Object request,
            int responseStatus,
            Class<T> responseType,
            Supplier<T> mutation) {
        requireIdempotencyKey(idempotencyKey);
        byte[] digest = digest(request);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                jdbc.query(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        resultSet -> { }, operation + ":" + idempotencyKey);
                List<StoredCommand> existing = jdbc.query(
                        "SELECT request_digest, response_json::text "
                                + "FROM app.product_idempotency_command "
                                + "WHERE operation = ? AND idempotency_key = ?",
                        (resultSet, rowNumber) -> new StoredCommand(
                                resultSet.getBytes(1), resultSet.getString(2)),
                        operation, idempotencyKey);
                if (!existing.isEmpty()) {
                    if (!MessageDigest.isEqual(digest, existing.get(0).digest())) {
                        throw new ProductApiException(
                                "IDEMPOTENCY_KEY_REUSED",
                                "Idempotency-Key was already used with a different request.",
                                HttpStatus.CONFLICT);
                    }
                    return decode(existing.get(0).responseJson(), responseType);
                }
                T response = mutation.get();
                jdbc.update(
                        "INSERT INTO app.product_idempotency_command "
                                + "(command_id, operation, idempotency_key, request_digest, "
                                + "response_status, response_json) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                        UUID.randomUUID(), operation, idempotencyKey, digest,
                        responseStatus, encode(response));
                return response;
            }));
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public ProductApiContract.ProjectResponse createProject(
            UUID traceId, ProductApiContract.CreateProjectRequest request) {
        return projects.createProject(traceId, request);
    }

    public ProductApiContract.ProjectResponse getProject(UUID projectId, UUID traceId) {
        return projects.getProject(projectId, traceId);
    }

    public List<ProductApiContract.ProjectResponse> listProjects(UUID traceId) {
        return projects.listProjects(traceId);
    }

    public ProductApiContract.ConnectorResponse createConnector(
            UUID projectId,
            UUID traceId,
            ProductApiContract.CreateConnectorRequest request) {
        return connectors.createConnector(projectId, traceId, request);
    }

    public ProductApiContract.ConnectorResponse getConnector(UUID connectorId, UUID traceId) {
        return connectors.getConnector(connectorId, traceId);
    }

    public List<ProductApiContract.ConnectorResponse> listConnectors(UUID projectId, UUID traceId) {
        return connectors.listConnectors(projectId, traceId);
    }

    public ProductApiContract.ConnectorPreviewResponse previewConnector(
            UUID connectorId,
            UUID traceId,
            ProductApiContract.ConnectorPreviewRequest request) {
        return connectors.previewConnector(connectorId, traceId, request);
    }

    public ProductApiContract.ConnectorResponse activateConnectorVersion(
            UUID connectorId, UUID versionId, UUID traceId) {
        return connectors.activateConnectorVersion(connectorId, versionId, traceId);
    }

    public ProductApiContract.KnowledgeBaseResponse createKnowledgeBase(
            UUID traceId, ProductApiContract.CreateKnowledgeBaseRequest request) {
        return knowledge.createKnowledgeBase(traceId, request);
    }

    public ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(UUID id, UUID traceId) {
        return knowledge.getKnowledgeBase(id, traceId);
    }

    public List<ProductApiContract.KnowledgeBaseResponse> listKnowledgeBases(
            UUID projectId, UUID traceId) {
        return knowledge.listKnowledgeBases(projectId, traceId);
    }

    public ProductApiContract.JobAcceptedResponse createConnectorSync(
            UUID connectorId,
            UUID traceId,
            ProductApiContract.ConnectorSyncRequest request) {
        return jobs.createConnectorSync(connectorId, traceId, request);
    }

    public ProductApiContract.JobAcceptedResponse createKnowledgeBuild(
            UUID knowledgeBaseId,
            UUID traceId,
            ProductApiContract.StartKnowledgeBuildRequest request) {
        return jobs.createKnowledgeBuild(knowledgeBaseId, traceId, request);
    }

    public ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(UUID id, UUID traceId) {
        return knowledge.getKnowledgeVersion(id, traceId);
    }

    public List<ProductApiContract.KnowledgeVersionResponse> listKnowledgeVersions(
            UUID knowledgeBaseId, UUID traceId) {
        return knowledge.listKnowledgeVersions(knowledgeBaseId, traceId);
    }

    public ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            UUID id, UUID traceId, Integer expectedStateVersion) {
        return knowledge.activateKnowledgeVersion(id, traceId, expectedStateVersion);
    }

    public ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            UUID knowledgeBaseId, UUID targetId, UUID traceId) {
        return knowledge.rollbackKnowledgeVersion(knowledgeBaseId, targetId, traceId);
    }

    public ProductApiContract.ChatbotResponse createChatbot(
            UUID projectId, UUID traceId, ProductApiContract.CreateChatbotRequest request) {
        return rag.createChatbot(projectId, traceId, request);
    }

    public List<ProductApiContract.ChatbotResponse> listChatbots(UUID projectId, UUID traceId) {
        return rag.listChatbots(projectId, traceId);
    }

    public ProductApiContract.ChatbotResponse getChatbot(UUID id, UUID traceId) {
        return rag.getChatbot(id, traceId);
    }

    public ProductApiContract.RagQueryResponse query(
            UUID chatbotId, UUID traceId, ProductApiContract.RagQueryRequest request) {
        return rag.query(chatbotId, traceId, request);
    }

    public ProductApiContract.AgentJobResponse getJob(UUID jobId, UUID traceId) {
        return jobs.getJob(jobId, traceId);
    }

    public List<ProductApiContract.AgentJobResponse> listJobs(UUID projectId, UUID traceId) {
        return jobs.listJobs(projectId, traceId);
    }

    public ProductApiContract.AgentJobResponse cancelJob(
            UUID jobId, UUID traceId, Integer expectedStateVersion) {
        return jobs.cancelJob(jobId, traceId, expectedStateVersion);
    }

    public ProductApiContract.AgentJobResponse retryJob(
            UUID jobId, UUID traceId, Integer expectedStateVersion) {
        return jobs.retryJob(jobId, traceId, expectedStateVersion);
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ProductApiException(
                    "CONTRACT_VALIDATION_FAILED",
                    "Idempotency-Key does not satisfy the public contract.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private byte[] digest(Object value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(value));
        }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("Cannot digest product command.", failure);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode product state.", failure);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored idempotency response is invalid.", failure);
        }
    }

    private record StoredCommand(byte[] digest, String responseJson) {
    }
}
