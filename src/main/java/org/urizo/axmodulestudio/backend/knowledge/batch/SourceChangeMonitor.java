package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorDocumentClient;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorSecretResolver;
import org.urizo.axmodulestudio.backend.knowledge.integration.DeterministicConnectorFixture;

/**
 * 원천 API 변경 점검(AXMS-AI02-022). 활성 버전의 커넥터로 원천을 다시 조회해
 * (공고 ID, 내용 해시) 차이 — 신규·수정·소멸 — 를 요약으로만 기록한다.
 *
 * <p><b>감시와 실행을 분리한다.</b> 이 클래스는 빌드·활성화·승인 코드를 부르지 않는다.
 * 요약은 관리자 화면의 "갱신이 필요합니다" 알림 재료이고, 갱신 요청은 일반 관리자가,
 * 빌드와 승인은 최고 관리자가 기존 흐름으로 한다. 자동화가 아니라 발견의 자동화다.
 *
 * <p><b>"소멸"은 삭제가 아니다.</b> 이 원천은 전체 중 최신 등록 N건의 창을 돌려주므로
 * (9/13 실측: 1,526건 중 500건), 활성 버전에는 있는데 최신 결과에 없는 문서는 대부분
 * 창 밖으로 밀려난 것이다. 어휘를 "삭제"로 쓰지 않는 이유다.
 *
 * <p>픽스처 원천(관광)은 건너뛴다 — 내장 표본은 바뀌지 않는다. 한 지식베이스의 점검
 * 실패는 로그만 남기고 다음 지식베이스로 넘어간다. 기본은 꺼짐이며, 켜도 검사당
 * 외부 API 호출 몇 번이 전부다(LLM 0회).
 */
@Component
@Profile("local-full")
public class SourceChangeMonitor {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SourceChangeMonitor.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConnectorDocumentClient connectors;
    private final ConnectorSecretResolver secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final int maxDocuments;

    SourceChangeMonitor(
            JdbcTemplate jdbc,
            // coding 쪽 TransactionTemplate이 둘 더 있고 어느 것도 @Primary가 아니다(AI02-020의 교훈).
            @Qualifier("productTransactionTemplate") TransactionTemplate transactions,
            ConnectorDocumentClient connectors,
            ConnectorSecretResolver secrets,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${ax.knowledge.source-monitor.enabled:false}") boolean enabled,
            @Value("${ax.knowledge.connector.max-documents:500}") int maxDocuments) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.connectors = connectors;
        this.secrets = secrets;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.enabled = enabled;
        this.maxDocuments = maxDocuments;
    }

    @Scheduled(
            initialDelayString = "${ax.knowledge.source-monitor.initial-delay:2m}",
            fixedDelayString = "${ax.knowledge.source-monitor.interval:6h}")
    public void check() {
        if (!enabled) {
            return;
        }
        for (Candidate candidate : candidates()) {
            try {
                checkOne(candidate);
            }
            catch (RuntimeException failure) {
                // 원천 장애·키 문제로 한 고객사가 막혀도 나머지 점검은 계속한다.
                LOG.warn("Source change check failed: kb='{}' kind={} reason={}",
                        candidate.name(), failure.getClass().getSimpleName(), failure.getMessage());
            }
        }
    }

    /** 활성 버전과 그 버전에 고정된 커넥터 설정이 있는 지식베이스만 점검 대상이다. */
    private List<Candidate> candidates() {
        return jdbc.query(
                "SELECT kb.knowledge_base_id, kb.name, kv.knowledge_version_id, kv.version_number, "
                        + "cv.config_json::text "
                        + "FROM app.knowledge_base kb "
                        + "JOIN app.knowledge_version kv ON kv.knowledge_version_id = kb.active_version_id "
                        + "JOIN app.connector_version cv ON cv.connector_version_id = kv.connector_version_id",
                (rs, row) -> new Candidate(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5)));
    }

    private void checkOne(Candidate candidate) {
        JsonNode config = parse(candidate.configJson());
        if (DeterministicConnectorFixture.supports(config.path("baseUrl").asText())) {
            return;
        }
        String key = secrets.resolve(config.path("authentication").path("secretRef").asText());
        List<ProductApiContract.PreviewDocument> latest =
                connectors.fetch(config, key, maxDocuments);

        Map<String, String> active = new HashMap<>();
        jdbc.query(
                "SELECT external_document_id, content_digest FROM app.source_document "
                        + "WHERE knowledge_version_id = ?",
                rs -> {
                    active.put(rs.getString(1), rs.getString(2));
                }, candidate.versionId());

        boolean comparable = digestsComparable(config, candidate.versionId());
        Diff diff = diff(active, latest, comparable);
        Instant now = Instant.now(clock);
        String summary = encode(now, candidate.versionNumber(), diff);
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE app.knowledge_base SET source_change_summary = ?::jsonb, updated_at = ? "
                        + "WHERE knowledge_base_id = ?",
                summary, Timestamp.from(now), candidate.knowledgeBaseId()));
        LOG.info("Source change check: kb='{}' comparedVersion=v{} added={} modified={} missing={}{}",
                candidate.name(), candidate.versionNumber(),
                diff.added(), diff.modified(), diff.missing(),
                comparable ? "" : " (modified not compared — merged or enriched corpus)");
    }

    /**
     * 저장된 본문 해시를 목록 응답과 맞대 볼 수 있는가(AXMS-AI02-023).
     *
     * <p>이 점검은 목록 API만 부른다. 그런데 빌드는 그 목록에 OVERLAY 원천을 합치거나
     * 문서별 상세를 덧붙여 저장하므로, 그런 버전의 저장 본문은 목록 본문과 <b>구조적으로
     * 다르다</b> — 원천이 하나도 바뀌지 않아도 전건이 "수정됨"으로 나온다(관광 500/500 실측).
     *
     * <p>그래서 그 경우 수정 집계를 하지 않는다. <b>신규·소멸은 문서 번호만 보므로 그대로
     * 유효하다.</b> 상세까지 다시 받아 비교하면 점검 한 번에 문서 수만큼 외부 호출이 들어
     * 점검 자체가 원천에 부담이 된다 — 감지 목적에 맞지 않는 비용이다.
     */
    private boolean digestsComparable(JsonNode config, UUID versionId) {
        if (config.path("documentMapping").has("detail")) {
            return false;
        }
        Integer overlays = jdbc.queryForObject(
                "SELECT count(*) FROM app.knowledge_version_connector "
                        + "WHERE knowledge_version_id = ? AND role <> 'BASE'",
                Integer.class, versionId);
        return overlays == null || overlays == 0;
    }

    /**
     * (ID, 해시) 대조. 해시는 수집이 저장할 때 쓰는 {@link ProductBatchService#sha256}
     * 그대로다 — 다른 자로 재면 모든 문서가 "수정됨"이 된다.
     *
     * @param digestsComparable 저장 본문이 목록 본문과 같은 방식으로 만들어졌는가.
     *     거짓이면 수정 집계를 건너뛴다({@link #digestsComparable}).
     */
    static Diff diff(Map<String, String> activeDigestsById,
            List<ProductApiContract.PreviewDocument> latest, boolean digestsComparable) {
        int added = 0;
        int modified = 0;
        Set<String> seen = new HashSet<>();
        for (ProductApiContract.PreviewDocument document : latest) {
            seen.add(document.documentId());
            String activeDigest = activeDigestsById.get(document.documentId());
            if (activeDigest == null) {
                added++;
            }
            else if (digestsComparable
                    && !activeDigest.equals(ProductBatchService.sha256(document.content()))) {
                modified++;
            }
        }
        int missing = (int) activeDigestsById.keySet().stream()
                .filter(id -> !seen.contains(id)).count();
        return new Diff(added, modified, missing);
    }

    private String encode(Instant checkedAt, int comparedVersion, Diff diff) {
        try {
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("checkedAt", checkedAt.toString())
                    .put("comparedVersion", comparedVersion)
                    .put("added", diff.added())
                    .put("modified", diff.modified())
                    .put("missing", diff.missing()));
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Source change summary could not be encoded.", impossible);
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored connector configuration is invalid.", failure);
        }
    }

    record Candidate(
            UUID knowledgeBaseId, String name, UUID versionId, int versionNumber,
            String configJson) { }

    record Diff(int added, int modified, int missing) { }
}
