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
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

@Repository
@Profile("local-full")
public class KnowledgeStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ProjectStore projects;

    KnowledgeStore(
            JdbcTemplate productJdbcTemplate,
            TransactionTemplate productTransactionTemplate,
            Clock clock,
            ProjectStore projects) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
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
        resolveOpenActivationRequests(row.knowledgeBaseId(), now);
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
        resolveOpenActivationRequests(knowledgeBaseId, now);
        return getKnowledgeVersion(targetId, traceId);
    }

    /**
     * 자료 갱신 요청을 남긴다. 같은 사람이 같은 대상으로 이미 열어 둔 요청이 있으면
     * 새 행을 만들지 않고 그것을 돌려준다 — 재촉이 목록을 늘리면 읽히지 않는다.
     */
    public ProductApiContract.ActivationRequestResponse createActivationRequest(
            UUID knowledgeBaseId,
            UUID traceId,
            org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor actor,
            ProductApiContract.CreateActivationRequestRequest request) {
        getKnowledgeBase(knowledgeBaseId, traceId);
        UUID versionId = request.knowledgeVersionId();
        if (versionId != null) {
            // 다른 지식 베이스의 버전을 지목한 요청은 목록에서 의미가 없다.
            one(jdbc.query(
                    "SELECT knowledge_version_id FROM app.knowledge_version "
                            + "WHERE knowledge_version_id = ? AND knowledge_base_id = ?",
                    (rs, row) -> rs.getObject(1, UUID.class), versionId, knowledgeBaseId),
                    "KNOWLEDGE_VERSION_NOT_FOUND", "Knowledge version not found.");
        }
        // 트랜잭션으로 감싼다. productDataSource가 autoCommit=false라 트랜잭션 밖의 INSERT는
        // 예외 없이 커밋되지 않고 커넥션 반납 시 롤백된다 — 201을 받고도 행이 남지 않는다.
        // 다른 쓰기는 store.idempotent(...)가 트랜잭션을 열어 주는데, 이 경로는 멱등 Key를
        // 요구하지 않기로 해서 그 진입점을 함께 잃었다.
        //
        // 조회와 INSERT를 한 트랜잭션에 둔다. 둘이 갈라지면 같은 사람이 동시에 두 번 눌렀을 때
        // 양쪽 다 "열린 요청 없음"을 보고 INSERT로 진입해 부분 유니크 인덱스에 부딪힌다.
        return transactions.execute(status -> {
            List<ProductApiContract.ActivationRequestResponse> existing = jdbc.query(
                    activationRequestSelect()
                            + " WHERE knowledge_base_id = ? AND requested_by = ? AND status = 'OPEN' "
                            + "AND knowledge_version_id IS NOT DISTINCT FROM ?",
                    (rs, row) -> activationRequest(rs, traceId), knowledgeBaseId, actor.actorId(), versionId);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID requestId = UUID.randomUUID();
            Instant now = Instant.now(clock);
            jdbc.update(
                    "INSERT INTO app.knowledge_activation_request "
                            + "(request_id, knowledge_base_id, knowledge_version_id, reason, status, "
                            + "requested_by, requested_by_name, created_at) "
                            + "VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)",
                    requestId, knowledgeBaseId, versionId, blankToNull(request.reason()),
                    actor.actorId(), actor.name(), Timestamp.from(now));
            return new ProductApiContract.ActivationRequestResponse(
                    version(), traceId, requestId, knowledgeBaseId, versionId,
                    blankToNull(request.reason()), "OPEN", actor.actorId(), actor.name(), now);
        });
    }

    public List<ProductApiContract.ActivationRequestResponse> listOpenActivationRequests(
            UUID knowledgeBaseId, UUID traceId) {
        getKnowledgeBase(knowledgeBaseId, traceId);
        return jdbc.query(
                activationRequestSelect()
                        + " WHERE knowledge_base_id = ? AND status = 'OPEN' ORDER BY created_at DESC",
                (rs, row) -> activationRequest(rs, traceId), knowledgeBaseId);
    }

    /**
     * 활성화·롤백이 곧 요청 처리다. 별도 처리 엔드포인트를 두지 않는다 — 두면 "바꿨는데
     * 요청은 열려 있는" 상태가 생기고, 그 상태를 맞추는 일이 다시 사람 몫이 된다.
     */
    private void resolveOpenActivationRequests(UUID knowledgeBaseId, Instant now) {
        jdbc.update(
                "UPDATE app.knowledge_activation_request SET status = 'RESOLVED', resolved_at = ? "
                        + "WHERE knowledge_base_id = ? AND status = 'OPEN'",
                Timestamp.from(now), knowledgeBaseId);
    }

    private String activationRequestSelect() {
        return "SELECT request_id, knowledge_base_id, knowledge_version_id, reason, status, "
                + "requested_by, requested_by_name, created_at FROM app.knowledge_activation_request";
    }

    private ProductApiContract.ActivationRequestResponse activationRequest(
            ResultSet rs, UUID traceId) throws SQLException {
        return new ProductApiContract.ActivationRequestResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), instant(rs, 8));
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
