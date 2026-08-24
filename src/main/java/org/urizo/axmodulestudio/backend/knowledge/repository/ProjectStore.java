package org.urizo.axmodulestudio.backend.knowledge.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

@Repository
@Profile("local-full")
public class ProjectStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ProjectStore(JdbcTemplate productJdbcTemplate, Clock clock) {
        this.jdbc = productJdbcTemplate;
        this.clock = clock;
    }

    public ProductApiContract.ProjectResponse createProject(
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

    public ProductApiContract.ProjectResponse getProject(UUID projectId, UUID traceId) {
        return one(jdbc.query(
                "SELECT project_id, name, description, status, created_at FROM app.project "
                        + "WHERE project_id = ?",
                (rs, row) -> project(rs, traceId), projectId));
    }

    public List<ProductApiContract.ProjectResponse> listProjects(UUID traceId) {
        return jdbc.query(
                "SELECT project_id, name, description, status, created_at FROM app.project "
                        + "ORDER BY created_at, project_id",
                (rs, row) -> project(rs, traceId));
    }

    public void requireProject(UUID projectId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.project WHERE project_id = ?", Integer.class, projectId);
        if (count == null || count == 0) {
            throw new ProductApiException(
                    "PROJECT_NOT_FOUND", "Project not found.", HttpStatus.NOT_FOUND);
        }
    }

    private static ProductApiContract.ProjectResponse project(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.ProjectResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant());
    }

    private static ProductApiContract.ProjectResponse one(
            List<ProductApiContract.ProjectResponse> values) {
        if (values.isEmpty()) {
            throw new ProductApiException(
                    "PROJECT_NOT_FOUND", "Project not found.", HttpStatus.NOT_FOUND);
        }
        return values.get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String version() {
        return ProductApiContract.SCHEMA_VERSION;
    }
}
