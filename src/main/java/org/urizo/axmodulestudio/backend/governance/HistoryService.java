package org.urizo.axmodulestudio.backend.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded reads from each domain's existing datasource; no new cross-domain DB grants. */
@Service
@Profile("local-full")
public class HistoryService {
    private final JdbcTemplate product;
    private final ObjectProvider<JdbcTemplate> coding;
    private final Clock clock;

    public HistoryService(
            @Qualifier("productJdbcTemplate") JdbcTemplate product,
            @Qualifier("codingModelTurnJdbcTemplate") ObjectProvider<JdbcTemplate> coding,
            Clock clock) {
        this.product = product;
        this.coding = coding;
        this.clock = clock;
    }

    public HistoryContract.Page approvals(HistoryContract.Domain domain, String query,
            String cursor, int limit) {
        String search = search(query);
        String scope = fingerprint("approvals:" + domain + ":" + search);
        Boundary boundary = boundary(cursor, scope);
        int bounded = limit(limit);
        List<Row> rows = switch (domain) {
            case RAG -> read(product, RAG_APPROVALS, search, boundary, bounded);
            case LLM_OPS -> read(coding(), CODING_APPROVALS, search, boundary, bounded);
            case NATURAL_CMS -> read(product, NATURAL_APPROVALS, search, boundary, bounded);
        };
        return page(rows, bounded, scope);
    }

    public HistoryContract.Page runs(HistoryContract.Category category, String query,
            String cursor, int limit) {
        String search = search(query);
        String scope = fingerprint("runs:" + category + ":" + search);
        Boundary boundary = boundary(cursor, scope);
        int bounded = limit(limit);
        List<Row> rows = new ArrayList<>();
        if (category != HistoryContract.Category.AI) {
            rows.addAll(read(product, CMS_CHANGES, search, boundary, bounded));
        }
        if (category != HistoryContract.Category.CMS) {
            rows.addAll(read(product, PRODUCT_JOBS, search, boundary, bounded));
            rows.addAll(read(product, NATURAL_JOBS, search, boundary, bounded));
            rows.addAll(read(coding(), CODING_JOBS, search, boundary, bounded));
        }
        return page(rows, bounded, scope);
    }

    private JdbcTemplate coding() {
        JdbcTemplate result = coding.getIfAvailable();
        if (result == null) {
            throw new HistoryUnavailableException();
        }
        return result;
    }

    private List<Row> read(JdbcTemplate jdbc, String source, String query, Boundary boundary, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM (" + source + ") h WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!query.isEmpty()) {
            sql.append(" AND position(lower(?) in lower(concat_ws(' ', title, target_id, job_id::text, status))) > 0");
            params.add(query);
        }
        if (boundary != null) {
            sql.append(" AND (sort_at < ? OR (sort_at = ? AND id COLLATE \"C\" < ?))");
            params.add(Timestamp.from(boundary.at()));
            params.add(Timestamp.from(boundary.at()));
            params.add(boundary.id());
        }
        // Same tie ordering as Java's comparator: all record identifiers are ASCII.
        sql.append(" ORDER BY sort_at DESC, id COLLATE \"C\" DESC LIMIT ?");
        params.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, index) -> row(rs), params.toArray());
    }

    private HistoryContract.Page page(List<Row> source, int limit, String scope) {
        List<Row> ordered = source.stream().sorted(Comparator.comparing(Row::sortAt)
                .thenComparing(row -> row.entry().id()).reversed()).toList();
        List<Row> selected = ordered.stream().limit(limit).toList();
        String next = null;
        if (ordered.size() > limit) {
            Row last = selected.get(selected.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (scope + "|" + last.sortAt() + "|" + last.entry().id()).getBytes(StandardCharsets.UTF_8));
        }
        return new HistoryContract.Page(selected.stream().map(Row::entry).toList(), next, clock.instant());
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(time(rs, "sort_at"), new HistoryContract.Entry(
                rs.getString("id"), rs.getString("domain"), rs.getString("kind"), rs.getString("title"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getObject("job_id", UUID.class),
                rs.getString("status"), rs.getString("job_status"), rs.getString("stage"),
                (Integer) rs.getObject("attempt"), (Integer) rs.getObject("state_version"),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("actor_role"),
                time(rs, "created_at"), time(rs, "occurred_at"), time(rs, "started_at"),
                time(rs, "updated_at"), time(rs, "finished_at"), rs.getString("feedback"),
                rs.getString("error_code"), rs.getString("coverage")));
    }

    private static Instant time(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static int limit(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("limit must be 1..100");
        return value;
    }

    private static String search(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 200) throw new IllegalArgumentException("query is too long");
        return result;
    }

    private static Boundary boundary(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 400) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !scope.equals(parts[0])
                    || !parts[2].matches("[a-z-]+:[a-f0-9-]{36}(?::[0-9]+:[a-f0-9-]{36})?")) throw new IllegalArgumentException();
            return new Boundary(Instant.parse(parts[1]), parts[2]);
        }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid history cursor.");
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Boundary(Instant at, String id) { }
    private record Row(Instant sortAt, HistoryContract.Entry entry) { }
    public static final class HistoryUnavailableException extends RuntimeException { }

    static final String RAG_APPROVALS = """
            SELECT 'rag-version:' || kv.knowledge_version_id AS id, 'RAG' AS domain,
                'KNOWLEDGE_VERSION' AS kind, kb.name || ' · v' || kv.version_number AS title,
                'KNOWLEDGE_VERSION' AS target_type, kv.knowledge_version_id::text AS target_id,
                kv.build_job_id AS job_id, kv.status, pj.status AS job_status,
                'ACTIVATION' AS stage, NULL::integer AS attempt, pj.state_version,
                NULL::uuid AS actor_id, NULL::text AS actor_name, NULL::text AS actor_role,
                kv.created_at, kv.activated_at AS occurred_at, NULL::timestamptz AS started_at,
                COALESCE(kv.archived_at, kv.activated_at, kv.ready_at, kv.created_at) AS updated_at,
                NULL::timestamptz AS finished_at, NULL::text AS feedback, NULL::text AS error_code,
                'VERSION_STATE' AS coverage,
                COALESCE(kv.archived_at, kv.activated_at, kv.ready_at, kv.created_at) AS sort_at
            FROM app.knowledge_version kv
            JOIN app.knowledge_base kb ON kb.knowledge_base_id = kv.knowledge_base_id
            LEFT JOIN app.product_job pj ON pj.job_id = kv.build_job_id
            WHERE kv.status IN ('APPROVAL_PENDING', 'ACTIVE', 'ARCHIVED')
            """;

    static final String CODING_APPROVALS = """
            SELECT 'coding-approval:' || d.job_id || ':' || d.pipeline_attempt || ':' || d.approval_id AS id, 'LLM_OPS' AS domain,
                'APPROVAL_DECISION' AS kind, left(COALESCE(r.request_text, j.job_id::text), 240) AS title,
                'REPOSITORY' AS target_type, j.repository_id::text AS target_id, j.job_id,
                d.decision AS status, j.status AS job_status, d.stage, d.pipeline_attempt AS attempt,
                j.state_version, d.actor_id, NULL::text AS actor_name, d.actor_role,
                j.created_at, d.decided_at AS occurred_at, NULL::timestamptz AS started_at,
                j.updated_at, NULL::timestamptz AS finished_at, d.feedback, NULL::text AS error_code,
                'DECISION_RECORD' AS coverage, d.decided_at AS sort_at
            FROM app.coding_approval_decision d
            JOIN app.coding_job j ON j.job_id = d.job_id
            LEFT JOIN app.coding_job_request r ON r.job_id = j.job_id
            """;

    static final String NATURAL_APPROVALS = """
            SELECT 'natural-approval:' || job_id AS id, 'NATURAL_CMS' AS domain,
                'LATEST_APPROVAL' AS kind, left(request_text, 240) AS title,
                resource_type AS target_type, resource_id AS target_id, job_id,
                approval_decision AS status, status AS job_status, 'PREVIEW' AS stage,
                NULL::integer AS attempt, state_version, approver_id AS actor_id,
                NULL::text AS actor_name, NULL::text AS actor_role, created_at,
                NULL::timestamptz AS occurred_at, NULL::timestamptz AS started_at,
                updated_at, NULL::timestamptz AS finished_at, approval_feedback AS feedback,
                NULL::text AS error_code, 'LATEST_ONLY' AS coverage, updated_at AS sort_at
            FROM app.natural_cms_job WHERE approval_decision IS NOT NULL
            """;

    static final String CMS_CHANGES = """
            SELECT 'cms-change:' || change_id AS id, 'CMS' AS domain, operation AS kind, title,
                resource_type AS target_type, resource_id AS target_id, NULL::uuid AS job_id,
                'SUCCEEDED' AS status, NULL::text AS job_status, NULL::text AS stage,
                NULL::integer AS attempt, NULL::integer AS state_version, actor_id, actor_name, actor_role,
                occurred_at AS created_at, occurred_at, NULL::timestamptz AS started_at,
                occurred_at AS updated_at, occurred_at AS finished_at,
                NULL::text AS feedback, NULL::text AS error_code, 'CHANGE_RECORD' AS coverage,
                occurred_at AS sort_at FROM app.cms_change_history
            """;

    static final String PRODUCT_JOBS = """
            SELECT 'product-job:' || job_id AS id, 'RAG' AS domain, job_type AS kind,
                job_type || ' · ' || job_id AS title, 'PROJECT' AS target_type,
                project_id::text AS target_id, job_id, status, status AS job_status, phase AS stage,
                attempt, state_version, NULL::uuid AS actor_id, NULL::text AS actor_name,
                NULL::text AS actor_role, created_at, NULL::timestamptz AS occurred_at,
                started_at, updated_at, finished_at, NULL::text AS feedback,
                failure_code AS error_code, 'JOB_STATE' AS coverage, created_at AS sort_at
            FROM app.product_job
            """;

    static final String CODING_JOBS = """
            SELECT 'coding-job:' || j.job_id AS id, 'LLM_OPS' AS domain, j.job_type AS kind,
                left(COALESCE(r.request_text, j.job_id::text), 240) AS title,
                'REPOSITORY' AS target_type, j.repository_id::text AS target_id, j.job_id,
                j.status, j.status AS job_status, j.graph_step AS stage,
                (SELECT max(pipeline_attempt) FROM app.coding_pipeline_attempt a WHERE a.job_id = j.job_id) AS attempt,
                j.state_version, j.actor_id, NULL::text AS actor_name, NULL::text AS actor_role,
                j.created_at, NULL::timestamptz AS occurred_at, j.started_at, j.updated_at, j.finished_at,
                NULL::text AS feedback, j.failure_code AS error_code, 'JOB_STATE' AS coverage,
                j.created_at AS sort_at
            FROM app.coding_job j LEFT JOIN app.coding_job_request r ON r.job_id = j.job_id
            WHERE j.job_type = 'CODING_AGENT'
            """;

    static final String NATURAL_JOBS = """
            SELECT 'natural-job:' || job_id AS id, 'NATURAL_CMS' AS domain, 'NATURAL_CMS_JOB' AS kind,
                left(request_text, 240) AS title, resource_type AS target_type, resource_id AS target_id,
                job_id, status, status AS job_status, NULL::text AS stage, pipeline_attempt AS attempt,
                state_version, actor_id, NULL::text AS actor_name, NULL::text AS actor_role,
                created_at, NULL::timestamptz AS occurred_at, NULL::timestamptz AS started_at,
                updated_at, NULL::timestamptz AS finished_at, NULL::text AS feedback,
                NULL::text AS error_code, 'JOB_STATE' AS coverage, created_at AS sort_at
            FROM app.natural_cms_job
            """;
}
