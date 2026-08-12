package org.urizo.axmodulestudio.backend.product;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("local-full")
public class ProductStore {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");
    private static final TypeReference<List<ProductApiContract.ResourceRef>> RESOURCE_REFS =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProductRuntimeProperties properties;

    ProductStore(
            JdbcTemplate productJdbcTemplate,
            TransactionTemplate productTransactionTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            ProductRuntimeProperties properties) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    <T> T idempotent(
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
                        "SELECT request_digest, response_json::text FROM app.product_idempotency_command "
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

    ProductApiContract.ProjectResponse createProject(
            UUID traceId, ProductApiContract.CreateProjectRequest request) {
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                "INSERT INTO app.project "
                        + "(project_id, name, description, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                projectId, request.name().trim(), blankToNull(request.description()),
                Timestamp.from(now), Timestamp.from(now));
        return new ProductApiContract.ProjectResponse(
                version(), traceId, projectId, request.name().trim(),
                blankToNull(request.description()), "ACTIVE", now);
    }

    ProductApiContract.ProjectResponse getProject(UUID projectId, UUID traceId) {
        return one(jdbc.query(
                "SELECT project_id, name, description, status, created_at FROM app.project "
                        + "WHERE project_id = ?",
                (rs, row) -> project(rs, traceId), projectId), "PROJECT_NOT_FOUND", "Project not found.");
    }

    List<ProductApiContract.ProjectResponse> listProjects(UUID traceId) {
        return jdbc.query(
                "SELECT project_id, name, description, status, created_at FROM app.project "
                        + "ORDER BY created_at, project_id",
                (rs, row) -> project(rs, traceId));
    }

    ProductApiContract.ConnectorResponse createConnector(
            UUID projectId,
            UUID traceId,
            ProductApiContract.CreateConnectorRequest request) {
        requireProject(projectId);
        validateConnector(request);
        UUID connectorId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        ObjectNode config = objectMapper.createObjectNode();
        config.put("baseUrl", request.baseUrl().toString());
        config.put("endpoint", request.endpoint());
        config.put("method", request.method());
        config.set("authentication", request.authentication());
        config.set("requestParameters", objectMapper.valueToTree(request.requestParameters()));
        config.set("response", request.response());
        config.set("pagination", request.pagination());
        config.set("documentMapping", request.documentMapping());
        String configJson = encode(config);
        String configDigest = sha256(configJson);
        jdbc.update(
                "INSERT INTO app.connector "
                        + "(connector_id, project_id, name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DRAFT', ?, ?)",
                connectorId, projectId, request.name(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update(
                "INSERT INTO app.connector_version "
                        + "(connector_version_id, connector_id, version_number, status, "
                        + "config_json, config_digest, created_at) "
                        + "VALUES (?, ?, 1, 'DRAFT', ?::jsonb, ?, ?)",
                versionId, connectorId, configJson, configDigest, Timestamp.from(now));
        return new ProductApiContract.ConnectorResponse(
                version(), traceId, projectId, connectorId, versionId,
                request.name(), "DRAFT", configDigest, now);
    }

    ProductApiContract.ConnectorResponse getConnector(UUID connectorId, UUID traceId) {
        return one(jdbc.query(connectorSelect() + " WHERE c.connector_id = ?",
                (rs, row) -> connector(rs, traceId), connectorId),
                "CONNECTOR_NOT_FOUND", "Connector not found.");
    }

    List<ProductApiContract.ConnectorResponse> listConnectors(UUID projectId, UUID traceId) {
        requireProject(projectId);
        return jdbc.query(connectorSelect() + " WHERE c.project_id = ? ORDER BY c.created_at, c.connector_id",
                (rs, row) -> connector(rs, traceId), projectId);
    }

    ConnectorConfig connectorConfig(UUID connectorId) {
        return one(jdbc.query(
                "SELECT c.connector_id, c.project_id, cv.connector_version_id, cv.status, "
                        + "cv.config_json::text, cv.config_digest "
                        + "FROM app.connector c JOIN app.connector_version cv ON cv.connector_id = c.connector_id "
                        + "WHERE c.connector_id = ? "
                        + "ORDER BY (cv.connector_version_id = c.active_version_id) DESC, "
                        + "cv.version_number DESC LIMIT 1",
                (rs, row) -> new ConnectorConfig(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        decodeTree(rs.getString(5)), rs.getString(6)), connectorId),
                "CONNECTOR_NOT_FOUND", "Connector not found.");
    }

    ProductApiContract.ConnectorPreviewResponse previewConnector(
            UUID connectorId,
            UUID traceId,
            ProductApiContract.ConnectorPreviewRequest request) {
        ConnectorConfig config = connectorConfig(connectorId);
        requireFixture(config.config().path("baseUrl").asText());
        List<ProductApiContract.PreviewDocument> documents =
                DeterministicConnectorFixture.documents(request.maxItems());
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE app.connector_version SET previewed_at = ? "
                        + "WHERE connector_version_id = ?",
                Timestamp.from(now), config.connectorVersionId());
        return new ProductApiContract.ConnectorPreviewResponse(
                version(), traceId, connectorId, documents.size(),
                DeterministicConnectorFixture.totalCount(), documents,
                documents.size() < DeterministicConnectorFixture.totalCount(), now);
    }

    ProductApiContract.ConnectorResponse activateConnectorVersion(
            UUID connectorId, UUID versionId, UUID traceId) {
        ConnectorVersionRow row = one(jdbc.query(
                "SELECT c.project_id, c.name, c.active_version_id, cv.status, "
                        + "cv.config_digest, cv.created_at "
                        + "FROM app.connector c JOIN app.connector_version cv "
                        + "ON cv.connector_id = c.connector_id "
                        + "WHERE c.connector_id = ? AND cv.connector_version_id = ? FOR UPDATE OF c, cv",
                (rs, index) -> new ConnectorVersionRow(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), instant(rs, 6)), connectorId, versionId),
                "CONNECTOR_VERSION_NOT_FOUND", "Connector version not found.");
        if (!"DRAFT".equals(row.status()) && !"ACTIVE".equals(row.status())) {
            throw conflict("CONNECTOR_VERSION_NOT_ACTIVATABLE", "Connector version cannot be activated.");
        }
        Instant now = Instant.now(clock);
        if (row.activeVersionId() != null && !row.activeVersionId().equals(versionId)) {
            jdbc.update("UPDATE app.connector_version SET status = 'ARCHIVED' "
                    + "WHERE connector_version_id = ?", row.activeVersionId());
        }
        jdbc.update("UPDATE app.connector_version SET status = 'ACTIVE', activated_at = COALESCE(activated_at, ?) "
                + "WHERE connector_version_id = ?", Timestamp.from(now), versionId);
        jdbc.update("UPDATE app.connector SET status = 'ACTIVE', active_version_id = ?, updated_at = ? "
                + "WHERE connector_id = ?", versionId, Timestamp.from(now), connectorId);
        return new ProductApiContract.ConnectorResponse(
                version(), traceId, row.projectId(), connectorId, versionId,
                row.name(), "ACTIVE", row.configDigest(), row.createdAt());
    }

    ProductApiContract.KnowledgeBaseResponse createKnowledgeBase(
            UUID traceId, ProductApiContract.CreateKnowledgeBaseRequest request) {
        requireProject(request.projectId());
        UUID knowledgeBaseId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                "INSERT INTO app.knowledge_base "
                        + "(knowledge_base_id, project_id, name, description, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                knowledgeBaseId, request.projectId(), request.name().trim(),
                blankToNull(request.description()), Timestamp.from(now), Timestamp.from(now));
        return new ProductApiContract.KnowledgeBaseResponse(
                version(), traceId, knowledgeBaseId, request.projectId(),
                request.name().trim(), blankToNull(request.description()), null, now);
    }

    ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(UUID id, UUID traceId) {
        return one(jdbc.query(
                "SELECT knowledge_base_id, project_id, name, description, active_version_id, created_at "
                        + "FROM app.knowledge_base WHERE knowledge_base_id = ?",
                (rs, row) -> knowledgeBase(rs, traceId), id),
                "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found.");
    }

    List<ProductApiContract.KnowledgeBaseResponse> listKnowledgeBases(UUID projectId, UUID traceId) {
        requireProject(projectId);
        return jdbc.query(
                "SELECT knowledge_base_id, project_id, name, description, active_version_id, created_at "
                        + "FROM app.knowledge_base WHERE project_id = ? ORDER BY created_at, knowledge_base_id",
                (rs, row) -> knowledgeBase(rs, traceId), projectId);
    }

    ProductApiContract.JobAcceptedResponse createConnectorSync(
            UUID connectorId,
            UUID traceId,
            ProductApiContract.ConnectorSyncRequest request) {
        ConnectorConfig config = connectorConfig(connectorId);
        if (!config.connectorVersionId().equals(request.connectorVersionId())
                || !"ACTIVE".equals(config.status())) {
            throw conflict("CONNECTOR_VERSION_NOT_ACTIVE", "An ACTIVE connector version is required.");
        }
        requireFixture(config.config().path("baseUrl").asText());
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        List<ProductApiContract.ResourceRef> refs = List.of(
                new ProductApiContract.ResourceRef("CONNECTOR", connectorId, null),
                new ProductApiContract.ResourceRef(
                        "CONNECTOR_VERSION", request.connectorVersionId(), config.configDigest()));
        insertProductJob(jobId, traceId, config.projectId(), "CONNECTOR_SYNC", refs, now);
        insertProductOutbox(jobId, traceId, "CONNECTOR_SYNC", 1);
        return accepted(traceId, jobId, "CONNECTOR_SYNC", now, null,
                request.connectorVersionId(), null);
    }

    ProductApiContract.JobAcceptedResponse createKnowledgeBuild(
            UUID knowledgeBaseId,
            UUID traceId,
            ProductApiContract.StartKnowledgeBuildRequest request) {
        KnowledgeBuildContext context = one(jdbc.query(
                "SELECT kb.project_id, kb.knowledge_base_id, cv.connector_version_id, "
                        + "cv.status, cv.config_digest, cv.config_json::text, c.project_id "
                        + "FROM app.knowledge_base kb CROSS JOIN app.connector_version cv "
                        + "JOIN app.connector c ON c.connector_id = cv.connector_id "
                        + "WHERE kb.knowledge_base_id = ? AND cv.connector_version_id = ? FOR UPDATE OF kb",
                (rs, row) -> new KnowledgeBuildContext(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        decodeTree(rs.getString(6)), rs.getObject(7, UUID.class)),
                knowledgeBaseId, request.connectorVersionId()),
                "BUILD_INPUT_NOT_FOUND", "Knowledge base or connector version not found.");
        if (!context.projectId().equals(context.connectorProjectId())) {
            throw conflict("PROJECT_SCOPE_MISMATCH", "Connector and knowledge base must belong to one project.");
        }
        if (!"ACTIVE".equals(context.connectorStatus())) {
            throw conflict("CONNECTOR_VERSION_NOT_ACTIVE", "An ACTIVE connector version is required.");
        }
        requireFixture(context.config().path("baseUrl").asText());
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) + 1 FROM app.knowledge_version "
                        + "WHERE knowledge_base_id = ?", Integer.class, knowledgeBaseId);
        UUID jobId = UUID.randomUUID();
        UUID knowledgeVersionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String digest = sha256(context.configDigest() + ":" + Objects.toString(request.label(), ""));
        List<ProductApiContract.ResourceRef> refs = List.of(
                new ProductApiContract.ResourceRef("KNOWLEDGE_BASE", knowledgeBaseId, null),
                new ProductApiContract.ResourceRef("KNOWLEDGE_VERSION", knowledgeVersionId, digest),
                new ProductApiContract.ResourceRef(
                        "CONNECTOR_VERSION", request.connectorVersionId(), context.configDigest()));
        insertProductJob(jobId, traceId, context.projectId(), "KNOWLEDGE_BUILD", refs, now);
        jdbc.update(
                "INSERT INTO app.knowledge_version "
                        + "(knowledge_version_id, knowledge_base_id, connector_version_id, build_job_id, "
                        + "version_number, label, status, config_digest, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'BUILD_REQUESTED', ?, ?)",
                knowledgeVersionId, knowledgeBaseId, request.connectorVersionId(), jobId,
                nextVersion, blankToNull(request.label()), digest, Timestamp.from(now));
        insertProductOutbox(jobId, traceId, "KNOWLEDGE_BUILD", 1);
        return accepted(traceId, jobId, "KNOWLEDGE_BUILD", now,
                knowledgeVersionId, request.connectorVersionId(), digest);
    }

    ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(UUID id, UUID traceId) {
        return one(jdbc.query(knowledgeVersionSelect() + " WHERE knowledge_version_id = ?",
                (rs, row) -> knowledgeVersion(rs, traceId), id),
                "KNOWLEDGE_VERSION_NOT_FOUND", "Knowledge version not found.");
    }

    List<ProductApiContract.KnowledgeVersionResponse> listKnowledgeVersions(
            UUID knowledgeBaseId, UUID traceId) {
        getKnowledgeBase(knowledgeBaseId, traceId);
        return jdbc.query(knowledgeVersionSelect()
                        + " WHERE knowledge_base_id = ? ORDER BY version_number DESC",
                (rs, row) -> knowledgeVersion(rs, traceId), knowledgeBaseId);
    }

    ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            UUID id, UUID traceId, Integer expectedStateVersion) {
        KnowledgeVersionActivation row = one(jdbc.query(
                "SELECT kv.knowledge_base_id, kb.active_version_id, kv.build_job_id, "
                        + "kv.status, pj.state_version FROM app.knowledge_version kv "
                        + "JOIN app.knowledge_base kb ON kb.knowledge_base_id = kv.knowledge_base_id "
                        + "LEFT JOIN app.product_job pj ON pj.job_id = kv.build_job_id "
                        + "WHERE kv.knowledge_version_id = ? FOR UPDATE OF kv, kb",
                (rs, index) -> new KnowledgeVersionActivation(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        (Integer) rs.getObject(5)), id),
                "KNOWLEDGE_VERSION_NOT_FOUND", "Knowledge version not found.");
        if (!"APPROVAL_PENDING".equals(row.status()) && !"ACTIVE".equals(row.status())) {
            throw conflict("KNOWLEDGE_VERSION_NOT_APPROVABLE", "Knowledge version is not awaiting approval.");
        }
        if (expectedStateVersion != null && !Objects.equals(expectedStateVersion, row.jobStateVersion())) {
            throw conflict("STATE_VERSION_CONFLICT", "The job state version has changed.");
        }
        Instant now = Instant.now(clock);
        if (row.activeVersionId() != null && !row.activeVersionId().equals(id)) {
            jdbc.update("UPDATE app.knowledge_version SET status = 'ARCHIVED', archived_at = ? "
                    + "WHERE knowledge_version_id = ?", Timestamp.from(now), row.activeVersionId());
        }
        jdbc.update("UPDATE app.knowledge_version SET status = 'ACTIVE', activated_at = ?, archived_at = NULL "
                + "WHERE knowledge_version_id = ?", Timestamp.from(now), id);
        jdbc.update("UPDATE app.knowledge_base SET active_version_id = ?, updated_at = ? "
                + "WHERE knowledge_base_id = ?", id, Timestamp.from(now), row.knowledgeBaseId());
        if (row.buildJobId() != null) {
            jdbc.update("UPDATE app.product_job SET status = 'SUCCEEDED', state_version = state_version + 1, "
                    + "progress_percent = 100, finished_at = ?, updated_at = ? "
                    + "WHERE job_id = ? AND status = 'WAITING_APPROVAL'",
                    Timestamp.from(now), Timestamp.from(now), row.buildJobId());
        }
        return getKnowledgeVersion(id, traceId);
    }

    ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            UUID knowledgeBaseId, UUID targetId, UUID traceId) {
        KnowledgeBaseActive current = one(jdbc.query(
                "SELECT active_version_id FROM app.knowledge_base WHERE knowledge_base_id = ? FOR UPDATE",
                (rs, row) -> new KnowledgeBaseActive(rs.getObject(1, UUID.class)), knowledgeBaseId),
                "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found.");
        String status = one(jdbc.query(
                "SELECT status FROM app.knowledge_version "
                        + "WHERE knowledge_base_id = ? AND knowledge_version_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), knowledgeBaseId, targetId),
                "KNOWLEDGE_VERSION_NOT_FOUND", "Rollback target not found.");
        if (!"ARCHIVED".equals(status) && !"ACTIVE".equals(status)) {
            throw conflict("ROLLBACK_TARGET_INVALID", "Rollback target must be a previously active version.");
        }
        Instant now = Instant.now(clock);
        if (current.activeVersionId() != null && !current.activeVersionId().equals(targetId)) {
            jdbc.update("UPDATE app.knowledge_version SET status = 'ARCHIVED', archived_at = ? "
                    + "WHERE knowledge_version_id = ?", Timestamp.from(now), current.activeVersionId());
        }
        jdbc.update("UPDATE app.knowledge_version SET status = 'ACTIVE', activated_at = ?, archived_at = NULL "
                + "WHERE knowledge_version_id = ?", Timestamp.from(now), targetId);
        jdbc.update("UPDATE app.knowledge_base SET active_version_id = ?, updated_at = ? "
                + "WHERE knowledge_base_id = ?", targetId, Timestamp.from(now), knowledgeBaseId);
        return getKnowledgeVersion(targetId, traceId);
    }

    ProductApiContract.ChatbotResponse createChatbot(
            UUID projectId, UUID traceId, ProductApiContract.CreateChatbotRequest request) {
        ProductApiContract.KnowledgeBaseResponse knowledge = getKnowledgeBase(
                request.knowledgeBaseId(), traceId);
        if (!knowledge.projectId().equals(projectId)) {
            throw conflict("PROJECT_SCOPE_MISMATCH", "Chatbot and knowledge base must belong to one project.");
        }
        UUID chatbotId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO app.chatbot_config "
                        + "(chatbot_id, project_id, knowledge_base_id, name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                chatbotId, projectId, request.knowledgeBaseId(), request.name().trim(),
                Timestamp.from(now), Timestamp.from(now));
        return new ProductApiContract.ChatbotResponse(
                version(), traceId, chatbotId, projectId, request.knowledgeBaseId(),
                request.name().trim(), "ACTIVE", now);
    }

    List<ProductApiContract.ChatbotResponse> listChatbots(UUID projectId, UUID traceId) {
        requireProject(projectId);
        return jdbc.query("SELECT chatbot_id, project_id, knowledge_base_id, name, status, created_at "
                        + "FROM app.chatbot_config WHERE project_id = ? ORDER BY created_at, chatbot_id",
                (rs, row) -> chatbot(rs, traceId), projectId);
    }

    ProductApiContract.ChatbotResponse getChatbot(UUID id, UUID traceId) {
        return one(jdbc.query("SELECT chatbot_id, project_id, knowledge_base_id, name, status, created_at "
                        + "FROM app.chatbot_config WHERE chatbot_id = ?",
                (rs, row) -> chatbot(rs, traceId), id),
                "CHATBOT_NOT_FOUND", "Chatbot not found.");
    }

    ProductApiContract.RagQueryResponse query(
            UUID chatbotId, UUID traceId, ProductApiContract.RagQueryRequest request) {
        ActiveKnowledge active = one(jdbc.query(
                "SELECT kb.active_version_id FROM app.chatbot_config cb "
                        + "JOIN app.knowledge_base kb ON kb.knowledge_base_id = cb.knowledge_base_id "
                        + "WHERE cb.chatbot_id = ? AND cb.status = 'ACTIVE'",
                (rs, row) -> new ActiveKnowledge(rs.getObject(1, UUID.class)), chatbotId),
                "CHATBOT_NOT_FOUND", "Chatbot not found.");
        if (active.versionId() == null) {
            throw conflict("ACTIVE_KNOWLEDGE_REQUIRED", "The chatbot has no active knowledge version.");
        }
        int topK = request.topK() == null ? 3 : request.topK();
        List<GroundingRow> rows = jdbc.query(
                "SELECT sd.external_document_id, sd.title, sd.source_url, dc.content, "
                        + "GREATEST(0, LEAST(1, 1 - (dc.embedding <=> ?::vector))) AS score "
                        + "FROM app.document_chunk dc JOIN app.source_document sd "
                        + "ON sd.source_document_id = dc.source_document_id "
                        + "WHERE dc.knowledge_version_id = ? AND dc.embedding IS NOT NULL "
                        + "ORDER BY dc.embedding <=> ?::vector, dc.document_chunk_id LIMIT ?",
                (rs, row) -> new GroundingRow(
                        rs.getString(1), rs.getString(2), URI.create(rs.getString(3)),
                        rs.getString(4), rs.getDouble(5)),
                DeterministicConnectorFixture.vector(request.query()), active.versionId(),
                DeterministicConnectorFixture.vector(request.query()), topK);
        List<GroundingRow> grounded = rows.stream()
                .filter(row -> DeterministicConnectorFixture.hasGroundingOverlap(
                        request.query(), row.content()))
                .toList();
        UUID conversationId = request.conversationId() == null
                ? UUID.randomUUID() : request.conversationId();
        Instant now = Instant.now(clock);
        if (grounded.isEmpty()) {
            return new ProductApiContract.RagQueryResponse(
                    version(), traceId, UUID.randomUUID(), conversationId,
                    "REFUSED", "활성 지식에서 답변을 뒷받침할 근거를 찾지 못했습니다.",
                    List.of(), active.versionId(), now);
        }
        GroundingRow first = grounded.get(0);
        List<ProductApiContract.Citation> citations = grounded.stream()
                .map(row -> new ProductApiContract.Citation(
                        row.documentId(), row.title(), row.sourceUrl(),
                        excerpt(row.content()), row.score()))
                .toList();
        return new ProductApiContract.RagQueryResponse(
                version(), traceId, UUID.randomUUID(), conversationId,
                "ANSWERED", "활성 지식의 근거에 따르면 " + first.content(),
                citations, active.versionId(), now);
    }

    ProductApiContract.AgentJobResponse getJob(UUID jobId, UUID traceId) {
        return one(jdbc.query(jobSelect() + " WHERE job_id = ?",
                (rs, row) -> job(rs, traceId), jobId),
                "AGENT_JOB_NOT_FOUND", "Agent job not found.");
    }

    List<ProductApiContract.AgentJobResponse> listJobs(UUID projectId, UUID traceId) {
        requireProject(projectId);
        return jdbc.query(jobSelect() + " WHERE project_id = ? ORDER BY created_at DESC, job_id",
                (rs, row) -> job(rs, traceId), projectId);
    }

    ProductApiContract.AgentJobResponse cancelJob(
            UUID jobId, UUID traceId, Integer expectedStateVersion) {
        JobState row = lockJob(jobId);
        requireExpectedVersion(row.stateVersion(), expectedStateVersion);
        if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(row.status())) {
            return getJob(jobId, traceId);
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE app.product_job SET status = 'CANCELLED', state_version = state_version + 1, "
                + "finished_at = ?, updated_at = ? WHERE job_id = ?",
                Timestamp.from(now), Timestamp.from(now), jobId);
        return getJob(jobId, traceId);
    }

    ProductApiContract.AgentJobResponse retryJob(
            UUID jobId, UUID traceId, Integer expectedStateVersion) {
        JobState row = lockJob(jobId);
        requireExpectedVersion(row.stateVersion(), expectedStateVersion);
        if (!"FAILED".equals(row.status()) || !Boolean.TRUE.equals(row.retryable())) {
            throw conflict("JOB_NOT_RETRYABLE", "Only a retryable failed job can be retried.");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE app.product_job SET status = 'QUEUED', state_version = state_version + 1, "
                + "phase = NULL, progress_percent = 0, next_attempt_at = ?, worker_id = NULL, "
                + "failure_code = NULL, failure_message = NULL, failure_retryable = NULL, "
                + "started_at = NULL, finished_at = NULL, updated_at = ? WHERE job_id = ?",
                Timestamp.from(now), Timestamp.from(now), jobId);
        insertProductOutbox(jobId, traceId, row.jobType(), row.stateVersion() + 1);
        return getJob(jobId, traceId);
    }

    private JobState lockJob(UUID jobId) {
        return one(jdbc.query(
                "SELECT status, state_version, job_type, failure_retryable "
                        + "FROM app.product_job WHERE job_id = ? FOR UPDATE",
                (rs, row) -> new JobState(
                        rs.getString(1), rs.getInt(2), rs.getString(3),
                        (Boolean) rs.getObject(4)), jobId),
                "AGENT_JOB_NOT_FOUND", "Agent job not found.");
    }

    private void insertProductJob(
            UUID jobId,
            UUID traceId,
            UUID projectId,
            String jobType,
            List<ProductApiContract.ResourceRef> refs,
            Instant now) {
        jdbc.update("INSERT INTO app.product_job "
                        + "(job_id, trace_id, project_id, job_type, status, state_version, "
                        + "progress_percent, resource_refs, created_at, updated_at, next_attempt_at) "
                        + "VALUES (?, ?, ?, ?, 'QUEUED', 1, 0, ?::jsonb, ?, ?, ?)",
                jobId, traceId, projectId, jobType, encode(refs),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertProductOutbox(UUID jobId, UUID traceId, String jobType, int attempt) {
        UUID outboxId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schemaVersion", version());
        payload.put("eventId", outboxId.toString());
        payload.put("jobId", jobId.toString());
        payload.put("traceId", traceId.toString());
        payload.put("jobType", jobType);
        payload.put("attempt", attempt);
        jdbc.update("INSERT INTO app.transactional_outbox "
                        + "(outbox_id, aggregate_type, aggregate_id, event_type, event_key, destination, "
                        + "payload, status, available_at, created_at, updated_at) "
                        + "VALUES (?, 'PRODUCT_JOB', ?, 'PRODUCT_JOB_REQUESTED', ?, ?, ?::jsonb, "
                        + "'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                outboxId, jobId, jobId + ":requested:v" + attempt,
                properties.productQueue(), encode(payload));
    }

    private ProductApiContract.JobAcceptedResponse accepted(
            UUID traceId,
            UUID jobId,
            String jobType,
            Instant now,
            UUID knowledgeVersionId,
            UUID connectorVersionId,
            String configDigest) {
        return new ProductApiContract.JobAcceptedResponse(
                version(), traceId, jobId, jobType, "QUEUED",
                "/api/agent-jobs/" + jobId, now,
                knowledgeVersionId, connectorVersionId, configDigest);
    }

    private ProductApiContract.ProjectResponse project(ResultSet rs, UUID traceId) throws SQLException {
        return new ProductApiContract.ProjectResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), instant(rs, 5));
    }

    private ProductApiContract.ConnectorResponse connector(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.ConnectorResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), instant(rs, 7));
    }

    private ProductApiContract.KnowledgeBaseResponse knowledgeBase(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.KnowledgeBaseResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), instant(rs, 6));
    }

    private ProductApiContract.KnowledgeVersionResponse knowledgeVersion(ResultSet rs, UUID traceId)
            throws SQLException {
        Number score = (Number) rs.getObject(12);
        return new ProductApiContract.KnowledgeVersionResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10),
                score == null ? null : score.doubleValue(), instant(rs, 11),
                nullableInstant(rs, 13), nullableInstant(rs, 14));
    }

    private ProductApiContract.ChatbotResponse chatbot(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.ChatbotResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), instant(rs, 6));
    }

    private ProductApiContract.AgentJobResponse job(ResultSet rs, UUID traceId) throws SQLException {
        ProductApiContract.JobFailure failure = rs.getString(16) == null ? null
                : new ProductApiContract.JobFailure(
                        rs.getString(16), rs.getString(17), rs.getBoolean(18),
                        rs.getBoolean(18) ? 1_000L : null);
        return new ProductApiContract.AgentJobResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getInt(5),
                new ProductApiContract.JobProgress(
                        rs.getString(6), rs.getInt(7), (Integer) rs.getObject(8),
                        (Integer) rs.getObject(9), (Integer) rs.getObject(10)),
                decodeResources(rs.getString(11)), failure,
                instant(rs, 12), nullableInstant(rs, 13), instant(rs, 14), nullableInstant(rs, 15));
    }

    private String connectorSelect() {
        return "SELECT c.project_id, c.connector_id, cv.connector_version_id, c.name, "
                + "cv.status, cv.config_digest, cv.created_at FROM app.connector c "
                + "JOIN app.connector_version cv ON cv.connector_version_id = COALESCE("
                + "c.active_version_id, (SELECT cv2.connector_version_id FROM app.connector_version cv2 "
                + "WHERE cv2.connector_id = c.connector_id ORDER BY cv2.version_number DESC LIMIT 1))";
    }

    private String knowledgeVersionSelect() {
        return "SELECT knowledge_version_id, knowledge_base_id, connector_version_id, build_job_id, "
                + "version_number, label, status, config_digest, document_count, chunk_count, "
                + "created_at, score, ready_at, activated_at FROM app.knowledge_version";
    }

    private String jobSelect() {
        return "SELECT job_id, project_id, job_type, status, state_version, phase, progress_percent, "
                + "target_count, success_count, failed_count, resource_refs::text, created_at, "
                + "started_at, updated_at, finished_at, failure_code, failure_message, failure_retryable "
                + "FROM app.product_job";
    }

    private void requireProject(UUID projectId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.project WHERE project_id = ?", Integer.class, projectId);
        if (count == null || count == 0) {
            throw new ProductApiException("PROJECT_NOT_FOUND", "Project not found.", HttpStatus.NOT_FOUND);
        }
    }

    private static void validateConnector(ProductApiContract.CreateConnectorRequest request) {
        if (!"GET".equals(request.method())) {
            throw validation("Only deterministic GET connectors are supported in the local profile.");
        }
        URI base = request.baseUrl().normalize();
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || base.toString().length() > 500) {
            throw validation("Connector baseUrl is not a canonical HTTPS origin or base path.");
        }
        if (!DeterministicConnectorFixture.supports(base.toString())) {
            throw validation("The local profile accepts only HTTPS fixture.invalid connector origins.");
        }
        if (request.endpoint().contains("..") || request.endpoint().startsWith("//")) {
            throw validation("Connector endpoint is not an origin-relative safe path.");
        }
        validateAuthentication(request.authentication());
        validateRequestParameters(request.requestParameters());
        validateResponseMapping(request.response());
        validatePagination(request.pagination());
        validateDocumentMapping(request.documentMapping());
        JsonNode secretRef = request.authentication().path("secretRef");
        if (!secretRef.isTextual() || !secretRef.asText().startsWith("fixture://")) {
            throw validation("The local fixture connector requires a non-secret fixture:// reference.");
        }
    }

    private static void validateAuthentication(JsonNode value) {
        requireFields(value,
                Set.of("type", "location", "name", "secretRef"), Set.of(), "authentication");
        if (!"API_KEY".equals(text(value, "type", 1, 120))
                || !Set.of("QUERY", "HEADER").contains(text(value, "location", 1, 120))) {
            throw validation("Connector authentication type or location is invalid.");
        }
        text(value, "name", 1, 120);
        String secretRef = text(value, "secretRef", 1, 500);
        if (!secretRef.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) {
            throw validation("Connector authentication secretRef is invalid.");
        }
    }

    private static void validateRequestParameters(List<JsonNode> parameters) {
        if (parameters.size() > 100) {
            throw validation("Connector requestParameters exceeds the contract limit.");
        }
        for (JsonNode parameter : parameters) {
            requireFields(parameter, Set.of("name", "type", "required"),
                    Set.of("description", "defaultValue"), "request parameter");
            text(parameter, "name", 1, 120);
            if (!Set.of("STRING", "INTEGER", "NUMBER", "BOOLEAN")
                    .contains(text(parameter, "type", 1, 120))
                    || !parameter.path("required").isBoolean()) {
                throw validation("Connector request parameter type is invalid.");
            }
            if (parameter.has("description")) {
                text(parameter, "description", 0, 500);
            }
            if (parameter.has("defaultValue")) {
                JsonNode defaultValue = parameter.get("defaultValue");
                if (!(defaultValue.isTextual() || defaultValue.isNumber() || defaultValue.isBoolean())) {
                    throw validation("Connector request parameter defaultValue is invalid.");
                }
            }
        }
    }

    private static void validateResponseMapping(JsonNode value) {
        requireFields(value, Set.of("itemsPath"),
                Set.of("successCodePath", "successValues", "totalCountPath"), "response mapping");
        jsonPath(value, "itemsPath");
        if (value.has("successCodePath")) {
            jsonPath(value, "successCodePath");
        }
        if (value.has("totalCountPath")) {
            jsonPath(value, "totalCountPath");
        }
        if (value.has("successValues")) {
            JsonNode successValues = value.get("successValues");
            if (!successValues.isArray() || successValues.isEmpty()) {
                throw validation("Connector response successValues is invalid.");
            }
            Set<JsonNode> unique = new HashSet<>();
            for (JsonNode item : successValues) {
                if (!(item.isTextual() || item.isIntegralNumber()) || !unique.add(item)) {
                    throw validation("Connector response successValues is invalid.");
                }
            }
        }
    }

    private static void validatePagination(JsonNode value) {
        requireFields(value,
                Set.of("type", "pageParameter", "pageSizeParameter", "startPage", "pageSize"),
                Set.of(), "pagination");
        if (!"PAGE".equals(text(value, "type", 1, 120))) {
            throw validation("Connector pagination type is invalid.");
        }
        text(value, "pageParameter", 1, 120);
        text(value, "pageSizeParameter", 1, 120);
        JsonNode startPage = value.path("startPage");
        JsonNode pageSize = value.path("pageSize");
        if (!startPage.isIntegralNumber() || !startPage.canConvertToInt()
                || startPage.intValue() < 0 || !pageSize.isIntegralNumber()
                || !pageSize.canConvertToInt() || pageSize.intValue() < 1
                || pageSize.intValue() > 1_000) {
            throw validation("Connector pagination bounds are invalid.");
        }
    }

    private static void validateDocumentMapping(JsonNode value) {
        Set<String> paths = Set.of(
                "documentId", "title", "content", "category", "sourceUpdatedAt", "sourceUrl");
        requireFields(value, Set.of("documentId", "title", "content"),
                Set.of("category", "sourceUpdatedAt", "sourceUrl", "metadata"),
                "document mapping");
        for (String field : paths) {
            if (value.has(field)) {
                jsonPath(value, field);
            }
        }
        if (value.has("metadata")) {
            JsonNode metadata = value.get("metadata");
            if (!metadata.isObject()) {
                throw validation("Connector document metadata mapping is invalid.");
            }
            metadata.fields().forEachRemaining(entry -> requireJsonPath(entry.getValue()));
        }
    }

    private static void requireFields(
            JsonNode value, Set<String> required, Set<String> optional, String label) {
        if (!value.isObject()) {
            throw validation("Connector " + label + " must be an object.");
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw validation("Connector " + label + " fields are invalid.");
        }
    }

    private static String text(JsonNode value, String field, int minimum, int maximum) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().length() < minimum
                || node.textValue().length() > maximum) {
            throw validation("Connector " + field + " is invalid.");
        }
        return node.textValue();
    }

    private static void jsonPath(JsonNode value, String field) {
        requireJsonPath(value.get(field));
    }

    private static void requireJsonPath(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || value.textValue().length() > 500 || !value.textValue().startsWith("$")) {
            throw validation("Connector JSONPath mapping is invalid.");
        }
    }

    private static void requireFixture(String baseUrl) {
        if (!DeterministicConnectorFixture.supports(baseUrl)) {
            throw new ProductApiException(
                    "CONNECTOR_FIXTURE_REQUIRED",
                    "Only the deterministic local connector adapter is enabled.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw validation("Idempotency-Key does not satisfy the public contract.");
        }
    }

    private static void requireExpectedVersion(int actual, Integer expected) {
        if (expected != null && expected != actual) {
            throw conflict("STATE_VERSION_CONFLICT", "The job state version has changed.");
        }
    }

    private byte[] digest(Object value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(value));
        }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("Cannot digest product command.", failure);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
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

    private JsonNode decodeTree(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored connector configuration is invalid.", failure);
        }
    }

    private List<ProductApiContract.ResourceRef> decodeResources(String json) {
        try {
            return objectMapper.readValue(json, RESOURCE_REFS);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored resource references are invalid.", failure);
        }
    }

    private static String version() {
        return ProductApiContract.SCHEMA_VERSION;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, int column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String excerpt(String content) {
        return content.length() <= 500 ? content : content.substring(0, 500);
    }

    private static <T> T one(List<T> values, String code, String message) {
        if (values.isEmpty()) {
            throw new ProductApiException(code, message, HttpStatus.NOT_FOUND);
        }
        return values.get(0);
    }

    private static ProductApiException validation(String message) {
        return new ProductApiException("CONTRACT_VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private static ProductApiException conflict(String code, String message) {
        return new ProductApiException(code, message, HttpStatus.CONFLICT);
    }

    record ConnectorConfig(
            UUID connectorId,
            UUID projectId,
            UUID connectorVersionId,
            String status,
            JsonNode config,
            String configDigest) {
    }

    private record StoredCommand(byte[] digest, String responseJson) { }
    private record ConnectorVersionRow(
            UUID projectId, String name, UUID activeVersionId, String status,
            String configDigest, Instant createdAt) { }
    private record KnowledgeBuildContext(
            UUID projectId, UUID knowledgeBaseId, UUID connectorVersionId,
            String connectorStatus, String configDigest, JsonNode config,
            UUID connectorProjectId) { }
    private record KnowledgeVersionActivation(
            UUID knowledgeBaseId, UUID activeVersionId, UUID buildJobId,
            String status, Integer jobStateVersion) { }
    private record KnowledgeBaseActive(UUID activeVersionId) { }
    private record ActiveKnowledge(UUID versionId) { }
    private record GroundingRow(
            String documentId, String title, URI sourceUrl, String content, double score) { }
    private record JobState(
            String status, int stateVersion, String jobType, Boolean retryable) { }
}
