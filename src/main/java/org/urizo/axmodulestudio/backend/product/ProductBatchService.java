package org.urizo.axmodulestudio.backend.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.connector.DeterministicConnectorFixture;

@Service
@Profile("local-full")
final class ProductBatchService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ProductBatchService(
            JdbcTemplate productJdbcTemplate,
            TransactionTemplate productTransactionTemplate,
            Clock clock) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
        this.clock = clock;
    }

    boolean claim(UUID jobId, String workerId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<String> states = jdbc.query(
                    "SELECT status FROM app.product_job WHERE job_id = ? FOR UPDATE",
                    (rs, row) -> rs.getString(1), jobId);
            if (states.isEmpty() || !"QUEUED".equals(states.get(0))) {
                return false;
            }
            Instant now = Instant.now(clock);
            return jdbc.update("UPDATE app.product_job SET status = 'RUNNING', "
                            + "state_version = state_version + 1, attempt = attempt + 1, "
                            + "worker_id = ?, started_at = COALESCE(started_at, ?), updated_at = ? "
                            + "WHERE job_id = ? AND status = 'QUEUED' AND next_attempt_at <= ? "
                            + "AND attempt < max_attempts",
                    workerId, Timestamp.from(now), Timestamp.from(now), jobId, Timestamp.from(now)) == 1;
        }));
    }

    int recoverInterruptedJobs(String workerId) {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);
            Integer exhausted = jdbc.queryForObject(
                    "WITH exhausted AS ("
                            + "UPDATE app.product_job SET status = 'FAILED', "
                            + "state_version = state_version + 1, worker_id = NULL, "
                            + "failure_code = 'INTERNAL_ERROR', "
                            + "failure_message = 'The local product batch exhausted restart attempts.', "
                            + "failure_retryable = FALSE, finished_at = ?, updated_at = ? "
                            + "WHERE status = 'RUNNING' AND worker_id = ? AND attempt >= max_attempts "
                            + "RETURNING job_id), failed_versions AS ("
                            + "UPDATE app.knowledge_version kv SET status = 'FAILED' FROM exhausted e "
                            + "WHERE kv.build_job_id = e.job_id "
                            + "AND kv.status IN ('BUILD_REQUESTED', 'BUILDING') "
                            + "RETURNING kv.knowledge_version_id) "
                            + "SELECT count(*) FROM exhausted",
                    Integer.class, Timestamp.from(now), Timestamp.from(now), workerId);
            int requeued = jdbc.update(
                    "UPDATE app.product_job SET status = 'QUEUED', state_version = state_version + 1, "
                            + "phase = NULL, progress_percent = 0, target_count = NULL, "
                            + "success_count = NULL, failed_count = NULL, next_attempt_at = ?, "
                            + "worker_id = NULL, batch_job_execution_id = NULL, "
                            + "failure_code = NULL, failure_message = NULL, failure_retryable = NULL, "
                            + "started_at = NULL, finished_at = NULL, updated_at = ? "
                            + "WHERE status = 'RUNNING' AND worker_id = ? AND attempt < max_attempts",
                    Timestamp.from(now), Timestamp.from(now), workerId);
            return (exhausted == null ? 0 : exhausted) + requeued;
        });
    }

    String jobType(UUID jobId) {
        List<String> values = jdbc.query(
                "SELECT job_type FROM app.product_job WHERE job_id = ?",
                (rs, row) -> rs.getString(1), jobId);
        return values.isEmpty() ? null : values.get(0);
    }

    UUID staleQueuedJob() {
        List<UUID> values = jdbc.query(
                "SELECT job_id FROM app.product_job WHERE status = 'QUEUED' "
                        + "AND next_attempt_at <= CURRENT_TIMESTAMP "
                        + "AND created_at < CURRENT_TIMESTAMP - INTERVAL '5 seconds' "
                        + "ORDER BY created_at LIMIT 1",
                (rs, row) -> rs.getObject(1, UUID.class));
        return values.isEmpty() ? null : values.get(0);
    }

    void recordBatchExecution(UUID jobId, long executionId) {
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE app.product_job SET batch_job_execution_id = ?, updated_at = ? "
                        + "WHERE job_id = ?",
                executionId, Timestamp.from(Instant.now(clock)), jobId));
    }

    void connectorSync(UUID jobId) {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            jdbc.update("UPDATE app.product_job SET status = 'SUCCEEDED', "
                            + "state_version = state_version + 1, phase = 'COLLECT', progress_percent = 100, "
                            + "target_count = ?, success_count = ?, failed_count = 0, finished_at = ?, updated_at = ? "
                            + "WHERE job_id = ? AND status = 'RUNNING'",
                    DeterministicConnectorFixture.totalCount(),
                    DeterministicConnectorFixture.totalCount(),
                    Timestamp.from(now), Timestamp.from(now), jobId);
        });
    }

    void phase(UUID jobId, String phase) {
        switch (phase) {
            case "COLLECT" -> collect(jobId);
            case "NORMALIZE" -> progress(jobId, phase, 30);
            case "CHUNK" -> chunk(jobId);
            case "EMBED" -> embed(jobId);
            case "INDEX" -> index(jobId);
            case "EVALUATE" -> evaluate(jobId);
            default -> throw new IllegalArgumentException("Unsupported product batch phase.");
        }
    }

    void fail(UUID jobId, String code, boolean retryable) {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            jdbc.update("UPDATE app.product_job SET status = 'FAILED', state_version = state_version + 1, "
                            + "failure_code = ?, failure_message = ?, failure_retryable = ?, "
                            + "finished_at = ?, updated_at = ? WHERE job_id = ? AND status = 'RUNNING'",
                    code, "The local product batch did not complete.", retryable,
                    Timestamp.from(now), Timestamp.from(now), jobId);
            jdbc.update("UPDATE app.knowledge_version SET status = 'FAILED' "
                    + "WHERE build_job_id = ? AND status IN ('BUILD_REQUESTED', 'BUILDING')", jobId);
        });
    }

    private void collect(UUID jobId) {
        transactions.executeWithoutResult(status -> {
            UUID versionId = knowledgeVersion(jobId);
            Instant now = Instant.now(clock);
            jdbc.update("UPDATE app.knowledge_version SET status = 'BUILDING' "
                    + "WHERE knowledge_version_id = ? AND status IN ('BUILD_REQUESTED', 'FAILED', 'BUILDING')",
                    versionId);
            for (ProductApiContract.PreviewDocument document
                    : DeterministicConnectorFixture.documents(20)) {
                UUID documentId = stableId(versionId + ":document:" + document.documentId());
                jdbc.update("INSERT INTO app.source_document "
                                + "(source_document_id, knowledge_version_id, external_document_id, title, "
                                + "content, category, source_url, source_updated_at, content_digest, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (knowledge_version_id, external_document_id) DO UPDATE SET "
                                + "title = EXCLUDED.title, content = EXCLUDED.content, category = EXCLUDED.category, "
                                + "source_url = EXCLUDED.source_url, source_updated_at = EXCLUDED.source_updated_at, "
                                + "content_digest = EXCLUDED.content_digest",
                        documentId, versionId, document.documentId(), document.title(), document.content(),
                        String.join(",", document.category()), document.sourceUrl().toString(),
                        Timestamp.from(document.sourceUpdatedAt()), sha256(document.content()), Timestamp.from(now));
            }
            updateProgress(jobId, "COLLECT", 15, DeterministicConnectorFixture.totalCount(),
                    DeterministicConnectorFixture.totalCount());
        });
    }

    private void chunk(UUID jobId) {
        transactions.executeWithoutResult(status -> {
            UUID versionId = knowledgeVersion(jobId);
            List<DocumentRow> documents = jdbc.query(
                    "SELECT source_document_id, content FROM app.source_document "
                            + "WHERE knowledge_version_id = ? ORDER BY external_document_id",
                    (rs, row) -> new DocumentRow(
                            rs.getObject(1, UUID.class), rs.getString(2)), versionId);
            for (DocumentRow document : documents) {
                UUID chunkId = stableId(document.documentId() + ":chunk:0");
                jdbc.update("INSERT INTO app.document_chunk "
                                + "(document_chunk_id, source_document_id, knowledge_version_id, chunk_index, "
                                + "content, content_digest) VALUES (?, ?, ?, 0, ?, ?) "
                                + "ON CONFLICT (source_document_id, chunk_index) DO UPDATE SET "
                                + "content = EXCLUDED.content, content_digest = EXCLUDED.content_digest",
                        chunkId, document.documentId(), versionId,
                        document.content(), sha256(document.content()));
            }
            updateProgress(jobId, "CHUNK", 45, documents.size(), documents.size());
        });
    }

    private void embed(UUID jobId) {
        transactions.executeWithoutResult(status -> {
            UUID versionId = knowledgeVersion(jobId);
            List<ChunkRow> chunks = jdbc.query(
                    "SELECT document_chunk_id, content FROM app.document_chunk "
                            + "WHERE knowledge_version_id = ? ORDER BY document_chunk_id",
                    (rs, row) -> new ChunkRow(
                            rs.getObject(1, UUID.class), rs.getString(2)), versionId);
            for (ChunkRow chunk : chunks) {
                jdbc.update("UPDATE app.document_chunk SET embedding = ?::vector "
                                + "WHERE document_chunk_id = ?",
                        DeterministicConnectorFixture.vector(chunk.content()), chunk.chunkId());
            }
            updateProgress(jobId, "EMBED", 65, chunks.size(), chunks.size());
        });
    }

    private void index(UUID jobId) {
        transactions.executeWithoutResult(status -> {
            UUID versionId = knowledgeVersion(jobId);
            Integer documents = jdbc.queryForObject(
                    "SELECT count(*) FROM app.source_document WHERE knowledge_version_id = ?",
                    Integer.class, versionId);
            Integer chunks = jdbc.queryForObject(
                    "SELECT count(*) FROM app.document_chunk WHERE knowledge_version_id = ? "
                            + "AND embedding IS NOT NULL",
                    Integer.class, versionId);
            jdbc.update("UPDATE app.knowledge_version SET document_count = ?, chunk_count = ? "
                            + "WHERE knowledge_version_id = ?",
                    documents, chunks, versionId);
            updateProgress(jobId, "INDEX", 85, chunks, chunks);
        });
    }

    private void evaluate(UUID jobId) {
        transactions.executeWithoutResult(status -> {
            UUID versionId = knowledgeVersion(jobId);
            Instant now = Instant.now(clock);
            jdbc.update("UPDATE app.knowledge_version SET status = 'APPROVAL_PENDING', score = 100, ready_at = ? "
                            + "WHERE knowledge_version_id = ? AND status = 'BUILDING'",
                    Timestamp.from(now), versionId);
            jdbc.update("UPDATE app.product_job SET status = 'WAITING_APPROVAL', "
                            + "state_version = state_version + 1, phase = 'APPROVAL_PENDING', "
                            + "progress_percent = 100, updated_at = ? "
                            + "WHERE job_id = ? AND status = 'RUNNING'",
                    Timestamp.from(now), jobId);
        });
    }

    private void progress(UUID jobId, String phase, int percent) {
        transactions.executeWithoutResult(status -> updateProgress(
                jobId, phase, percent, null, null));
    }

    private void updateProgress(
            UUID jobId, String phase, int percent, Integer target, Integer success) {
        jdbc.update("UPDATE app.product_job SET phase = ?, progress_percent = ?, "
                        + "target_count = COALESCE(?, target_count), "
                        + "success_count = COALESCE(?, success_count), failed_count = COALESCE(failed_count, 0), "
                        + "updated_at = ? WHERE job_id = ? AND status = 'RUNNING'",
                phase, percent, target, success, Timestamp.from(Instant.now(clock)), jobId);
    }

    private UUID knowledgeVersion(UUID jobId) {
        List<UUID> values = jdbc.query(
                "SELECT knowledge_version_id FROM app.knowledge_version WHERE build_job_id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), jobId);
        if (values.isEmpty()) {
            throw new IllegalStateException("Knowledge build job has no immutable target version.");
        }
        return values.get(0);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private record DocumentRow(UUID documentId, String content) { }
    private record ChunkRow(UUID chunkId, String content) { }
}
