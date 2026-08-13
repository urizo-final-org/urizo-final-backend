package org.urizo.axmodulestudio.backend.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.connector.ConnectorStore;
import org.urizo.axmodulestudio.backend.product.ProductApiContract;
import org.urizo.axmodulestudio.backend.product.ProductApiException;
import org.urizo.axmodulestudio.backend.product.ProductRuntimeProperties;
import org.urizo.axmodulestudio.backend.project.ProjectStore;

@Repository
@Profile("local-full")
public class ProductJobStore {

    private static final TypeReference<List<ProductApiContract.ResourceRef>> RESOURCE_REFS =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProductRuntimeProperties properties;
    private final ProjectStore projects;
    private final ConnectorStore connectors;

    ProductJobStore(
            JdbcTemplate productJdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            ProductRuntimeProperties properties,
            ProjectStore projects,
            ConnectorStore connectors) {
        this.jdbc = productJdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.projects = projects;
        this.connectors = connectors;
    }

    public ProductApiContract.JobAcceptedResponse createConnectorSync(
            UUID connectorId,
            UUID traceId,
            ProductApiContract.ConnectorSyncRequest request) {
        ConnectorStore.ConnectorConfig config = connectors.connectorConfig(connectorId);
        if (!config.connectorVersionId().equals(request.connectorVersionId())
                || !"ACTIVE".equals(config.status())) {
            throw conflict(
                    "CONNECTOR_VERSION_NOT_ACTIVE", "An ACTIVE connector version is required.");
        }
        connectors.requireFixture(config.config().path("baseUrl").asText());
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        List<ProductApiContract.ResourceRef> refs = List.of(
                new ProductApiContract.ResourceRef("CONNECTOR", connectorId, null),
                new ProductApiContract.ResourceRef(
                        "CONNECTOR_VERSION", request.connectorVersionId(), config.configDigest()));
        insertProductJob(jobId, traceId, config.projectId(), "CONNECTOR_SYNC", refs, now);
        insertProductOutbox(jobId, traceId, "CONNECTOR_SYNC", 1);
        return accepted(
                traceId, jobId, "CONNECTOR_SYNC", now, null,
                request.connectorVersionId(), null);
    }

    public ProductApiContract.JobAcceptedResponse createKnowledgeBuild(
            UUID knowledgeBaseId,
            UUID traceId,
            ProductApiContract.StartKnowledgeBuildRequest request) {
        KnowledgeBuildContext context = one(jdbc.query(
                "SELECT kb.project_id, kb.knowledge_base_id, cv.connector_version_id, "
                        + "cv.status, cv.config_digest, cv.config_json::text, c.project_id "
                        + "FROM app.knowledge_base kb CROSS JOIN app.connector_version cv "
                        + "JOIN app.connector c ON c.connector_id = cv.connector_id "
                        + "WHERE kb.knowledge_base_id = ? AND cv.connector_version_id = ? "
                        + "FOR UPDATE OF kb",
                (rs, row) -> new KnowledgeBuildContext(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        decodeTree(rs.getString(6)), rs.getObject(7, UUID.class)),
                knowledgeBaseId, request.connectorVersionId()),
                "BUILD_INPUT_NOT_FOUND", "Knowledge base or connector version not found.");
        if (!context.projectId().equals(context.connectorProjectId())) {
            throw conflict(
                    "PROJECT_SCOPE_MISMATCH",
                    "Connector and knowledge base must belong to one project.");
        }
        if (!"ACTIVE".equals(context.connectorStatus())) {
            throw conflict(
                    "CONNECTOR_VERSION_NOT_ACTIVE", "An ACTIVE connector version is required.");
        }
        connectors.requireFixture(context.config().path("baseUrl").asText());
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) + 1 FROM app.knowledge_version "
                        + "WHERE knowledge_base_id = ?",
                Integer.class, knowledgeBaseId);
        UUID jobId = UUID.randomUUID();
        UUID knowledgeVersionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String digest = sha256(
                context.configDigest() + ":" + Objects.toString(request.label(), ""));
        List<ProductApiContract.ResourceRef> refs = List.of(
                new ProductApiContract.ResourceRef("KNOWLEDGE_BASE", knowledgeBaseId, null),
                new ProductApiContract.ResourceRef(
                        "KNOWLEDGE_VERSION", knowledgeVersionId, digest),
                new ProductApiContract.ResourceRef(
                        "CONNECTOR_VERSION", request.connectorVersionId(), context.configDigest()));
        insertProductJob(jobId, traceId, context.projectId(), "KNOWLEDGE_BUILD", refs, now);
        jdbc.update(
                "INSERT INTO app.knowledge_version "
                        + "(knowledge_version_id, knowledge_base_id, connector_version_id, "
                        + "build_job_id, version_number, label, status, config_digest, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'BUILD_REQUESTED', ?, ?)",
                knowledgeVersionId, knowledgeBaseId, request.connectorVersionId(), jobId,
                nextVersion, blankToNull(request.label()), digest, Timestamp.from(now));
        insertProductOutbox(jobId, traceId, "KNOWLEDGE_BUILD", 1);
        return accepted(
                traceId, jobId, "KNOWLEDGE_BUILD", now,
                knowledgeVersionId, request.connectorVersionId(), digest);
    }

    public ProductApiContract.AgentJobResponse getJob(UUID jobId, UUID traceId) {
        return one(jdbc.query(jobSelect() + " WHERE job_id = ?",
                (rs, row) -> job(rs, traceId), jobId),
                "AGENT_JOB_NOT_FOUND", "Agent job not found.");
    }

    public List<ProductApiContract.AgentJobResponse> listJobs(UUID projectId, UUID traceId) {
        projects.requireProject(projectId);
        return jdbc.query(
                jobSelect() + " WHERE project_id = ? ORDER BY created_at DESC, job_id",
                (rs, row) -> job(rs, traceId), projectId);
    }

    public ProductApiContract.AgentJobResponse cancelJob(
            UUID jobId, UUID traceId, Integer expectedStateVersion) {
        JobState row = lockJob(jobId);
        requireExpectedVersion(row.stateVersion(), expectedStateVersion);
        if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(row.status())) {
            return getJob(jobId, traceId);
        }
        Instant now = Instant.now(clock);
        jdbc.update(
                "UPDATE app.product_job SET status = 'CANCELLED', "
                        + "state_version = state_version + 1, finished_at = ?, updated_at = ? "
                        + "WHERE job_id = ?",
                Timestamp.from(now), Timestamp.from(now), jobId);
        return getJob(jobId, traceId);
    }

    public ProductApiContract.AgentJobResponse retryJob(
            UUID jobId, UUID traceId, Integer expectedStateVersion) {
        JobState row = lockJob(jobId);
        requireExpectedVersion(row.stateVersion(), expectedStateVersion);
        if (!"FAILED".equals(row.status()) || !Boolean.TRUE.equals(row.retryable())) {
            throw conflict("JOB_NOT_RETRYABLE", "Only a retryable failed job can be retried.");
        }
        Instant now = Instant.now(clock);
        jdbc.update(
                "UPDATE app.product_job SET status = 'QUEUED', "
                        + "state_version = state_version + 1, phase = NULL, progress_percent = 0, "
                        + "next_attempt_at = ?, worker_id = NULL, failure_code = NULL, "
                        + "failure_message = NULL, failure_retryable = NULL, started_at = NULL, "
                        + "finished_at = NULL, updated_at = ? WHERE job_id = ?",
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
        jdbc.update(
                "INSERT INTO app.product_job "
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
        jdbc.update(
                "INSERT INTO app.transactional_outbox "
                        + "(outbox_id, aggregate_type, aggregate_id, event_type, event_key, "
                        + "destination, payload, status, available_at, created_at, updated_at) "
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

    private ProductApiContract.AgentJobResponse job(ResultSet rs, UUID traceId)
            throws SQLException {
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

    private String jobSelect() {
        return "SELECT job_id, project_id, job_type, status, state_version, phase, "
                + "progress_percent, target_count, success_count, failed_count, "
                + "resource_refs::text, created_at, started_at, updated_at, finished_at, "
                + "failure_code, failure_message, failure_retryable FROM app.product_job";
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode product state.", failure);
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

    private static void requireExpectedVersion(int actual, Integer expected) {
        if (expected != null && expected != actual) {
            throw conflict("STATE_VERSION_CONFLICT", "The job state version has changed.");
        }
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

    private static <T> T one(List<T> values, String code, String message) {
        if (values.isEmpty()) {
            throw new ProductApiException(code, message, HttpStatus.NOT_FOUND);
        }
        return values.get(0);
    }

    private static ProductApiException conflict(String code, String message) {
        return new ProductApiException(code, message, HttpStatus.CONFLICT);
    }

    private static String version() {
        return ProductApiContract.SCHEMA_VERSION;
    }

    private record KnowledgeBuildContext(
            UUID projectId,
            UUID knowledgeBaseId,
            UUID connectorVersionId,
            String connectorStatus,
            String configDigest,
            JsonNode config,
            UUID connectorProjectId) {
    }

    private record JobState(
            String status, int stateVersion, String jobType, Boolean retryable) {
    }
}
