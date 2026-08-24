package org.urizo.axmodulestudio.backend.knowledge.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

@Repository
@Profile("local-full")
public class KnowledgeStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProjectStore projects;

    KnowledgeStore(JdbcTemplate productJdbcTemplate, Clock clock, ProjectStore projects) {
        this.jdbc = productJdbcTemplate;
        this.clock = clock;
        this.projects = projects;
    }

    public ProductApiContract.KnowledgeBaseResponse createKnowledgeBase(
            UUID traceId, ProductApiContract.CreateKnowledgeBaseRequest request) {
        projects.requireProject(request.projectId());
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

    public ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(UUID id, UUID traceId) {
        return one(jdbc.query(
                "SELECT knowledge_base_id, project_id, name, description, active_version_id, created_at "
                        + "FROM app.knowledge_base WHERE knowledge_base_id = ?",
                (rs, row) -> knowledgeBase(rs, traceId), id),
                "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found.");
    }

    public List<ProductApiContract.KnowledgeBaseResponse> listKnowledgeBases(
            UUID projectId, UUID traceId) {
        projects.requireProject(projectId);
        return jdbc.query(
                "SELECT knowledge_base_id, project_id, name, description, active_version_id, created_at "
                        + "FROM app.knowledge_base WHERE project_id = ? "
                        + "ORDER BY created_at, knowledge_base_id",
                (rs, row) -> knowledgeBase(rs, traceId), projectId);
    }

    public ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(
            UUID id, UUID traceId) {
        return one(jdbc.query(knowledgeVersionSelect() + " WHERE knowledge_version_id = ?",
                (rs, row) -> knowledgeVersion(rs, traceId), id),
                "KNOWLEDGE_VERSION_NOT_FOUND", "Knowledge version not found.");
    }

    public List<ProductApiContract.KnowledgeVersionResponse> listKnowledgeVersions(
            UUID knowledgeBaseId, UUID traceId) {
        getKnowledgeBase(knowledgeBaseId, traceId);
        return jdbc.query(
                knowledgeVersionSelect()
                        + " WHERE knowledge_base_id = ? ORDER BY version_number DESC",
                (rs, row) -> knowledgeVersion(rs, traceId), knowledgeBaseId);
    }

    public ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
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
            throw conflict(
                    "KNOWLEDGE_VERSION_NOT_APPROVABLE",
                    "Knowledge version is not awaiting approval.");
        }
        if (expectedStateVersion != null
                && !Objects.equals(expectedStateVersion, row.jobStateVersion())) {
            throw conflict("STATE_VERSION_CONFLICT", "The job state version has changed.");
        }
        Instant now = Instant.now(clock);
        if (row.activeVersionId() != null && !row.activeVersionId().equals(id)) {
            jdbc.update("UPDATE app.knowledge_version SET status = 'ARCHIVED', archived_at = ? "
                    + "WHERE knowledge_version_id = ?", Timestamp.from(now), row.activeVersionId());
        }
        jdbc.update(
                "UPDATE app.knowledge_version SET status = 'ACTIVE', activated_at = ?, "
                        + "archived_at = NULL WHERE knowledge_version_id = ?",
                Timestamp.from(now), id);
        jdbc.update("UPDATE app.knowledge_base SET active_version_id = ?, updated_at = ? "
                + "WHERE knowledge_base_id = ?", id, Timestamp.from(now), row.knowledgeBaseId());
        if (row.buildJobId() != null) {
            jdbc.update(
                    "UPDATE app.product_job SET status = 'SUCCEEDED', "
                            + "state_version = state_version + 1, progress_percent = 100, "
                            + "finished_at = ?, updated_at = ? "
                            + "WHERE job_id = ? AND status = 'WAITING_APPROVAL'",
                    Timestamp.from(now), Timestamp.from(now), row.buildJobId());
        }
        return getKnowledgeVersion(id, traceId);
    }

    public ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            UUID knowledgeBaseId, UUID targetId, UUID traceId) {
        KnowledgeBaseActive current = one(jdbc.query(
                "SELECT active_version_id FROM app.knowledge_base "
                        + "WHERE knowledge_base_id = ? FOR UPDATE",
                (rs, row) -> new KnowledgeBaseActive(rs.getObject(1, UUID.class)), knowledgeBaseId),
                "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found.");
        String status = one(jdbc.query(
                "SELECT status FROM app.knowledge_version "
                        + "WHERE knowledge_base_id = ? AND knowledge_version_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), knowledgeBaseId, targetId),
                "KNOWLEDGE_VERSION_NOT_FOUND", "Rollback target not found.");
        if (!"ARCHIVED".equals(status) && !"ACTIVE".equals(status)) {
            throw conflict(
                    "ROLLBACK_TARGET_INVALID",
                    "Rollback target must be a previously active version.");
        }
        Instant now = Instant.now(clock);
        if (current.activeVersionId() != null && !current.activeVersionId().equals(targetId)) {
            jdbc.update("UPDATE app.knowledge_version SET status = 'ARCHIVED', archived_at = ? "
                    + "WHERE knowledge_version_id = ?",
                    Timestamp.from(now), current.activeVersionId());
        }
        jdbc.update(
                "UPDATE app.knowledge_version SET status = 'ACTIVE', activated_at = ?, "
                        + "archived_at = NULL WHERE knowledge_version_id = ?",
                Timestamp.from(now), targetId);
        jdbc.update("UPDATE app.knowledge_base SET active_version_id = ?, updated_at = ? "
                + "WHERE knowledge_base_id = ?",
                targetId, Timestamp.from(now), knowledgeBaseId);
        return getKnowledgeVersion(targetId, traceId);
    }

    private ProductApiContract.KnowledgeBaseResponse knowledgeBase(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.KnowledgeBaseResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), instant(rs, 6));
    }

    private ProductApiContract.KnowledgeVersionResponse knowledgeVersion(
            ResultSet rs, UUID traceId) throws SQLException {
        Number score = (Number) rs.getObject(12);
        return new ProductApiContract.KnowledgeVersionResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10),
                score == null ? null : score.doubleValue(), instant(rs, 11),
                nullableInstant(rs, 13), nullableInstant(rs, 14));
    }

    private String knowledgeVersionSelect() {
        return "SELECT knowledge_version_id, knowledge_base_id, connector_version_id, build_job_id, "
                + "version_number, label, status, config_digest, document_count, chunk_count, "
                + "created_at, score, ready_at, activated_at FROM app.knowledge_version";
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

    private record KnowledgeVersionActivation(
            UUID knowledgeBaseId,
            UUID activeVersionId,
            UUID buildJobId,
            String status,
            Integer jobStateVersion) {
    }

    private record KnowledgeBaseActive(UUID activeVersionId) {
    }
}
