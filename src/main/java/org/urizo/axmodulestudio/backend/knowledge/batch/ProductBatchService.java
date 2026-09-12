package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorDocumentClient;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorSecretResolver;
import org.urizo.axmodulestudio.backend.knowledge.integration.DeterministicConnectorFixture;
import org.urizo.axmodulestudio.backend.knowledge.integration.EmbeddingClient;
import org.urizo.axmodulestudio.backend.knowledge.integration.TourismSampleDocumentLoader;

@Service
@Profile("local-full")
final class ProductBatchService {

    // 평가 실패는 빌드를 죽이지 않고 건너뛴다. 로그가 없으면 "평가 없음"이 왜 났는지
    // 아무도 모른 채 활성화한다.
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ProductBatchService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final EmbeddingClient embeddings;
    private final ConnectorDocumentClient connectors;
    private final ConnectorSecretResolver secrets;
    private final ChunkingStrategyPlanner planner;
    private final ObjectMapper objectMapper;
    private final int maxDocuments;

    ProductBatchService(
            JdbcTemplate productJdbcTemplate,
            TransactionTemplate productTransactionTemplate,
            Clock clock,
            EmbeddingClient embeddings,
            ConnectorDocumentClient connectors,
            ConnectorSecretResolver secrets,
            ChunkingStrategyPlanner planner,
            ObjectMapper objectMapper,
            @Value("${ax.knowledge.connector.max-documents:500}") int maxDocuments) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
        this.clock = clock;
        this.embeddings = embeddings;
        this.connectors = connectors;
        this.secrets = secrets;
        this.planner = planner;
        this.objectMapper = objectMapper;
        this.maxDocuments = maxDocuments;
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
        UUID versionId = transactions.execute(status -> {
            UUID target = knowledgeVersion(jobId);
            jdbc.update("UPDATE app.knowledge_version SET status = 'BUILDING' "
                    + "WHERE knowledge_version_id = ? AND status IN ('BUILD_REQUESTED', 'FAILED', 'BUILDING')",
                    target);
            return target;
        });

        // 원천 조회는 500건에 HTTP 여러 번이라 트랜잭션 밖에서 한다. 안에 두면 수집이 끝날
        // 때까지 트랜잭션이 열려 있게 된다(EMBED가 배치 커밋을 쓰는 것과 같은 이유).
        List<ProductApiContract.PreviewDocument> documents = source(versionId);

        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            for (ProductApiContract.PreviewDocument document : documents) {
                UUID documentId = stableId(versionId + ":document:" + document.documentId());
                EventPeriod period = eventPeriod(document.content());
                jdbc.update("INSERT INTO app.source_document "
                                + "(source_document_id, knowledge_version_id, external_document_id, title, "
                                + "content, category, source_url, source_updated_at, content_digest, created_at, "
                                + "event_start_date, event_end_date, image_url) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (knowledge_version_id, external_document_id) DO UPDATE SET "
                                + "title = EXCLUDED.title, content = EXCLUDED.content, category = EXCLUDED.category, "
                                + "source_url = EXCLUDED.source_url, source_updated_at = EXCLUDED.source_updated_at, "
                                + "content_digest = EXCLUDED.content_digest, "
                                + "event_start_date = EXCLUDED.event_start_date, "
                                + "event_end_date = EXCLUDED.event_end_date, "
                                + "image_url = EXCLUDED.image_url",
                        documentId, versionId, document.documentId(), document.title(), document.content(),
                        String.join(",", document.category()), document.sourceUrl().toString(),
                        Timestamp.from(document.sourceUpdatedAt()), sha256(document.content()), Timestamp.from(now),
                        period.start(), period.end(), document.imageUrl());
            }
            updateProgress(jobId, "COLLECT", 15, documents.size(), documents.size());
        });
    }

    /**
     * 이 Version에 고정된 커넥터로 원천 문서를 가져온다.
     *
     * <p>{@code fixture.invalid} 커넥터는 기존 표본 로더를 그대로 쓴다. 1호(관광) 코퍼스가
     * 그 경로로 적재돼 있어, 실수집으로 한번에 갈아타면 이미 활성화된 Version을 다시 만들 수
     * 없게 된다. 1호를 커넥터로 재수집할 수 있음이 확인되면 그때 이 분기와 로더를 함께 지운다.
     */
    private List<ProductApiContract.PreviewDocument> source(UUID versionId) {
        JsonNode config = connectorConfig(versionId);
        if (DeterministicConnectorFixture.supports(config.path("baseUrl").asText())) {
            return TourismSampleDocumentLoader.documents();
        }
        String key = secrets.resolve(
                config.path("authentication").path("secretRef").asText());
        return connectors.fetch(config, key, maxDocuments);
    }

    private JsonNode connectorConfig(UUID versionId) {
        List<String> values = jdbc.query(
                "SELECT cv.config_json::text FROM app.knowledge_version kv "
                        + "JOIN app.connector_version cv "
                        + "ON cv.connector_version_id = kv.connector_version_id "
                        + "WHERE kv.knowledge_version_id = ?",
                (rs, row) -> rs.getString(1), versionId);
        if (values.isEmpty()) {
            throw new IllegalStateException("Knowledge version has no pinned connector version.");
        }
        try {
            return objectMapper.readTree(values.get(0));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored connector configuration is invalid.", failure);
        }
    }

    /**
     * 전략 결정은 트랜잭션 밖에서 한다. LLM 호출을 트랜잭션 안에 두면 응답을 기다리는 동안
     * 연결을 붙잡는다(COLLECT가 HTTP 수집을 밖에 둔 것과 같은 이유).
     *
     * <p>이미 전략이 저장된 버전은 다시 묻지 않는다. 재빌드가 매번 다른 값을 받으면 같은
     * 버전을 재현할 수 없고, 무엇이 바뀌어 결과가 달라졌는지도 말할 수 없다.
     */
    private void chunk(UUID jobId) {
        UUID versionId = transactions.execute(status -> knowledgeVersion(jobId));
        List<DocumentRow> documents = jdbc.query(
                "SELECT source_document_id, content FROM app.source_document "
                        + "WHERE knowledge_version_id = ? ORDER BY external_document_id",
                (rs, row) -> new DocumentRow(
                        rs.getObject(1, UUID.class), rs.getString(2)), versionId);
        ChunkingStrategy strategy = storedStrategy(versionId)
                .orElseGet(() -> planner.plan(documents.stream().map(DocumentRow::content).toList()));

        transactions.executeWithoutResult(status -> {
            int chunks = 0;
            for (DocumentRow document : documents) {
                List<String> pieces = split(document.content(), strategy);
                for (int index = 0; index < pieces.size(); index++) {
                    String piece = pieces.get(index);
                    UUID chunkId = stableId(document.documentId() + ":chunk:" + index);
                    jdbc.update("INSERT INTO app.document_chunk "
                                    + "(document_chunk_id, source_document_id, knowledge_version_id, chunk_index, "
                                    + "content, content_digest) VALUES (?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT (source_document_id, chunk_index) DO UPDATE SET "
                                    + "content = EXCLUDED.content, content_digest = EXCLUDED.content_digest",
                            chunkId, document.documentId(), versionId, index,
                            piece, sha256(piece));
                }
                chunks += pieces.size();
            }
            // 이전 빌드가 더 잘게 쪼갰다면 남은 꼬리 청크를 지운다. 남겨 두면 사라진 전략의
            // 청크가 검색에 계속 걸린다.
            jdbc.update("DELETE FROM app.document_chunk dc USING app.source_document sd "
                            + "WHERE dc.source_document_id = sd.source_document_id "
                            + "AND sd.knowledge_version_id = ? AND dc.chunk_index >= ?",
                    versionId, maxChunkIndex(documents, strategy) + 1);
            saveStrategy(versionId, strategy);
            updateProgress(jobId, "CHUNK", 45, chunks, chunks);
        });
    }

    /**
     * 문서 제목으로 검색해 그 문서가 상위에 돌아오는지 센다. 실패하면 null.
     *
     * <p>표본은 등록 순서에서 고르게 건너뛰며 뽑는다 — 앞 N건만 쓰면 수집 순서가 특정 분야에
     * 몰렸을 때 그 분야만 재게 된다.
     */
    private BuildEvaluation measure(UUID versionId) {
        try {
            List<SampleRow> samples = jdbc.query(
                    "SELECT source_document_id, title FROM app.source_document "
                            + "WHERE knowledge_version_id = ? AND title IS NOT NULL AND title <> '' "
                            + "ORDER BY external_document_id",
                    (rs, row) -> new SampleRow(rs.getObject(1, UUID.class), rs.getString(2)),
                    versionId);
            if (samples.isEmpty()) {
                return null;
            }
            int stride = Math.max(1, samples.size() / BuildEvaluation.MAX_SAMPLE);
            List<Integer> ranks = new ArrayList<>();
            for (int index = 0; index < samples.size() && ranks.size() < BuildEvaluation.MAX_SAMPLE;
                    index += stride) {
                ranks.add(rankOf(versionId, samples.get(index)));
            }
            return BuildEvaluation.of(ranks);
        }
        catch (RuntimeException failure) {
            // 색인은 이미 만들어졌다. 측정이 안 됐다고 빌드를 실패로 돌리지 않는다.
            LOG.warn("Build evaluation skipped: kind={} reason={}",
                    failure.getClass().getSimpleName(), failure.getMessage());
            return null;
        }
    }

    /** 상위 {@code DEPTH}건에서 자기 문서의 1-기반 순위. 없으면 0. */
    private int rankOf(UUID versionId, SampleRow sample) {
        List<UUID> found = jdbc.query(
                "SELECT dc.source_document_id FROM app.document_chunk dc "
                        + "WHERE dc.knowledge_version_id = ? AND dc.embedding IS NOT NULL "
                        + "ORDER BY dc.embedding <=> ?::vector, dc.document_chunk_id LIMIT ?",
                (rs, row) -> rs.getObject(1, UUID.class),
                versionId, embeddings.queryVector(sample.title()), BuildEvaluation.DEPTH);
        // 한 문서가 여러 청크를 가지므로 문서 단위로 접은 뒤 순위를 센다. 접지 않으면
        // 같은 문서의 청크가 상위를 채워 순위가 실제보다 좋아 보인다.
        List<UUID> distinct = found.stream().distinct().toList();
        int rank = distinct.indexOf(sample.documentId());
        return rank < 0 ? 0 : rank + 1;
    }

    private String encode(BuildEvaluation evaluation) {
        try {
            return objectMapper.writeValueAsString(evaluation);
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Build evaluation could not be encoded.", impossible);
        }
    }

    private record SampleRow(UUID documentId, String title) { }

    private static int maxChunkIndex(List<DocumentRow> documents, ChunkingStrategy strategy) {
        return documents.stream()
                .mapToInt(document -> split(document.content(), strategy).size() - 1)
                .max().orElse(0);
    }

    /**
     * 문단 경계 우선으로 자른다. 상한에서 기계적으로 끊으면 문장이 반토막 나 근거 인용이
     * 어색해진다. 문단이 상한보다 길면 그때만 상한에서 끊는다.
     *
     * <p>겹침은 앞 조각의 꼬리를 다음 조각 머리에 붙인다 — 경계에 걸친 문장이 어느 쪽에서도
     * 온전하지 않게 되는 것을 막는다.
     */
    static List<String> split(String content, ChunkingStrategy strategy) {
        if (strategy.splitsNothing() || content.length() <= strategy.maxCharacters()) {
            return List.of(content);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\n{2,}")) {
            if (!current.isEmpty()
                    && current.length() + paragraph.length() + 2 > strategy.maxCharacters()) {
                pieces.add(current.toString().strip());
                current = new StringBuilder(tail(pieces.get(pieces.size() - 1), strategy));
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
            while (current.length() > strategy.maxCharacters()) {
                pieces.add(current.substring(0, strategy.maxCharacters()).strip());
                current = new StringBuilder(tail(pieces.get(pieces.size() - 1), strategy))
                        .append(current.substring(strategy.maxCharacters()));
            }
        }
        if (!current.toString().isBlank()) {
            pieces.add(current.toString().strip());
        }
        return pieces.isEmpty() ? List.of(content) : List.copyOf(pieces);
    }

    private static String tail(String piece, ChunkingStrategy strategy) {
        int overlap = Math.min(strategy.overlapCharacters(), piece.length());
        return overlap <= 0 ? "" : piece.substring(piece.length() - overlap);
    }

    private Optional<ChunkingStrategy> storedStrategy(UUID versionId) {
        return jdbc.query(
                "SELECT chunking_strategy::text FROM app.knowledge_version "
                        + "WHERE knowledge_version_id = ? AND chunking_strategy IS NOT NULL",
                (rs, row) -> rs.getString(1), versionId).stream()
                .findFirst()
                .map(json -> {
                    try {
                        JsonNode node = objectMapper.readTree(json);
                        return new ChunkingStrategy(
                                node.path("maxCharacters").asInt(0),
                                node.path("overlapCharacters").asInt(0),
                                node.path("reason").asText("저장된 전략."));
                    }
                    catch (JsonProcessingException invalid) {
                        throw new IllegalStateException("Stored chunking strategy is invalid.", invalid);
                    }
                });
    }

    private void saveStrategy(UUID versionId, ChunkingStrategy strategy) {
        try {
            jdbc.update("UPDATE app.knowledge_version SET chunking_strategy = ?::jsonb "
                            + "WHERE knowledge_version_id = ?",
                    objectMapper.writeValueAsString(strategy), versionId);
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Chunking strategy could not be encoded.", impossible);
        }
    }

    private void embed(UUID jobId) {
        UUID versionId = transactions.execute(status -> knowledgeVersion(jobId));
        List<ChunkRow> chunks = jdbc.query(
                "SELECT document_chunk_id, content FROM app.document_chunk "
                        + "WHERE knowledge_version_id = ? ORDER BY document_chunk_id",
                (rs, row) -> new ChunkRow(
                        rs.getObject(1, UUID.class), rs.getString(2)), versionId);

        // 임베딩 호출은 건당 수백 ms라 전량을 한 트랜잭션에 묶으면 수 분간 열려 있게 된다.
        // 배치 단위로 호출한 뒤 그 묶음만 짧게 커밋한다.
        int batchSize = embeddings.batchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<ChunkRow> slice = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            List<EmbeddingClient.Item> items = slice.stream()
                    .map(chunk -> new EmbeddingClient.Item(chunk.chunkId().toString(), chunk.content()))
                    .toList();

            // 차원·건수 검증은 클라이언트가 수행한다. 어긋나면 여기에 도달하지 않는다.
            Map<String, String> vectors = embeddings.batchVectors(items);

            transactions.executeWithoutResult(status -> {
                for (ChunkRow chunk : slice) {
                    // 순서가 아니라 id로 찾는다.
                    jdbc.update("UPDATE app.document_chunk SET embedding = ?::vector "
                                    + "WHERE document_chunk_id = ?",
                            vectors.get(chunk.chunkId().toString()), chunk.chunkId());
                }
            });
        }

        transactions.executeWithoutResult(status ->
                updateProgress(jobId, "EMBED", 65, chunks.size(), chunks.size()));
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

    /**
     * 색인이 실제로 검색되는지 재고 결과를 버전에 남긴다(AXMS-AI02-019).
     *
     * <p>임베딩 호출이 표본 수만큼 나가므로 트랜잭션 밖에서 잰다(COLLECT·CHUNK와 같은 이유).
     *
     * <p>측정이 실패해도 빌드를 죽이지 않는다 — 색인은 이미 만들어졌고, 평가가 없는 버전은
     * 화면에서 "평가 없음"으로 보이면 된다. 대신 로그로 남긴다.
     */
    private void evaluate(UUID jobId) {
        UUID targetVersion = transactions.execute(status -> knowledgeVersion(jobId));
        BuildEvaluation evaluation = measure(targetVersion);

        transactions.executeWithoutResult(status -> {
            UUID versionId = knowledgeVersion(jobId);
            Instant now = Instant.now(clock);
            jdbc.update("UPDATE app.knowledge_version SET status = 'APPROVAL_PENDING', "
                            + "score = ?, evaluation = ?::jsonb, ready_at = ? "
                            + "WHERE knowledge_version_id = ? AND status = 'BUILDING'",
                    evaluation == null ? null : evaluation.score(),
                    evaluation == null ? null : encode(evaluation),
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

    private static final Pattern EVENT_PERIOD =
            Pattern.compile("^\\[행사기간\\]\\s*(\\d{8})\\s*~\\s*(\\d{8})\\s*$", Pattern.MULTILINE);

    /** 본문의 "[행사기간] YYYYMMDD ~ YYYYMMDD" 줄. 없거나 형식·날짜가 어긋나면 NONE — 예외를 던지지 않는다. */
    static EventPeriod eventPeriod(String content) {
        Matcher matcher = EVENT_PERIOD.matcher(content);
        if (!matcher.find()) {
            return EventPeriod.NONE;
        }
        try {
            return new EventPeriod(
                    LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE),
                    LocalDate.parse(matcher.group(2), DateTimeFormatter.BASIC_ISO_DATE));
        }
        catch (DateTimeParseException invalid) {
            return EventPeriod.NONE;
        }
    }

    record EventPeriod(LocalDate start, LocalDate end) {
        static final EventPeriod NONE = new EventPeriod(null, null);
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
