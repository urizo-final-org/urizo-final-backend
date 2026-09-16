package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import org.urizo.axmodulestudio.backend.knowledge.integration.EmbeddingClient;

/**
 * 품질 진단 에이전트가 부르는 도구 세 개(AXMS-AI02-027).
 *
 * <p><b>관광 전용이다.</b> 모든 도구가 첫 줄에서 대상 버전이 관광 지식베이스 소속인지
 * 검사하고, 아니면 {@code OUT_OF_SCOPE}로 거절한다. 중기부 버전 UUID를 넣어도 조회
 * 자체가 성립하지 않는다 — 이번 작업이 중기부 동작에 닿지 않는다는 것을 문서가 아니라
 * 코드가 보장한다.
 *
 * <p><b>반환은 모델이 읽을 JSON이다.</b> 그래서 크기를 도구가 직접 막는다. 원문은 문서
 * 10건·600자에서 자르고, 채점 결과는 실패 문항만 20건까지 싣는다. 성공한 문항의 상세는
 * 넣지 않는다 — 진단에 쓰이지 않으면서 요청 상한(65,536자)만 먹는다.
 *
 * <p>도구는 상태를 갖지 않는다. 같은 인자로 다시 부르면 같은 답이 나온다 — 캐시·중복
 * 판정은 호출자({@link TourDiagnosisAgent})의 몫이다.
 */
@Component
@Profile("local-full")
public class TourDiagnosisTools {

    /** 이 이름만 에이전트에게 노출한다. 모델이 다른 이름을 부르면 호출자가 거절한다. */
    public static final String VERSION_OVERVIEW = "version_overview";
    public static final String SCORE_QUESTIONS = "score_questions";
    public static final String INSPECT_DOCUMENTS = "inspect_documents";

    /** 이 도구가 다루는 유일한 지식베이스. 다른 도메인은 스코프 검사에서 막힌다. */
    static final String TOUR_KNOWLEDGE_BASE = "관광 정보 지식베이스";

    /** 원문 조회 상한. 문서 수와 문서당 길이를 둘 다 막아야 요청 크기가 예측된다. */
    static final int MAX_DOCUMENTS = 10;
    static final int MAX_DOCUMENT_CHARACTERS = 600;
    /** 실패 문항 노출 상한. 50문항이 전부 실패해도 프롬프트가 터지지 않는다. */
    static final int MAX_FAILURES = 20;
    /** 채점에서 정답으로 인정하는 순위 상한. 저장된 hit5와 같은 기준을 쓴다. */
    private static final int HIT_DEPTH = 5;
    /** 순위를 재는 깊이. BuildEvaluation.DEPTH와 같은 값이어야 rank 값이 호환된다. */
    private static final int RANK_DEPTH = 10;

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddings;
    private final ObjectMapper objectMapper;

    TourDiagnosisTools(
            JdbcTemplate productJdbcTemplate,
            EmbeddingClient embeddings,
            ObjectMapper objectMapper) {
        this.jdbc = productJdbcTemplate;
        this.embeddings = embeddings;
        this.objectMapper = objectMapper;
    }

    /** 도구 하나를 실행한다. 실패는 예외가 아니라 {@code error} 필드로 돌려준다 —
     *  에이전트가 읽고 다음 수를 정해야 하기 때문이다. */
    public JsonNode call(String tool, JsonNode arguments) {
        try {
            return switch (tool) {
                case VERSION_OVERVIEW -> versionOverview(versionId(arguments));
                case SCORE_QUESTIONS -> scoreQuestions(versionId(arguments));
                case INSPECT_DOCUMENTS -> inspectDocuments(
                        versionId(arguments), documentIds(arguments));
                default -> error("UNKNOWN_TOOL", "그런 이름의 도구는 없습니다: " + tool);
            };
        }
        catch (IllegalArgumentException failure) {
            return error("INVALID_ARGUMENTS", failure.getMessage());
        }
        catch (RuntimeException failure) {
            // 임베딩 서버 정지·DB 오류 등. 원인을 모델에게 알려 주되 스택은 넘기지 않는다.
            return error("TOOL_FAILED", failure.getClass().getSimpleName());
        }
    }

    private JsonNode versionOverview(UUID versionId) {
        if (!inTourScope(versionId)) {
            return outOfScope();
        }
        List<ObjectNode> rows = jdbc.query(
                "SELECT kv.version_number, kv.status, kv.score, kv.evaluation::text, "
                        + "count(sd.source_document_id), "
                        + "COALESCE(round(avg(length(sd.content))), 0), "
                        + "COALESCE(min(length(sd.content)), 0), "
                        + "COALESCE(max(length(sd.content)), 0), "
                        + "count(*) FILTER (WHERE sd.content LIKE '%[개요]%'), "
                        + "count(*) FILTER (WHERE sd.image_url IS NOT NULL), "
                        + "count(*) FILTER (WHERE sd.event_start_date IS NOT NULL), "
                        + "count(*) FILTER (WHERE sd.category IS NOT NULL), "
                        + "count(*) FILTER (WHERE sd.source_url IS NOT NULL) "
                        + "FROM app.knowledge_version kv "
                        + "LEFT JOIN app.source_document sd "
                        + "ON sd.knowledge_version_id = kv.knowledge_version_id "
                        + "WHERE kv.knowledge_version_id = ? "
                        + "GROUP BY kv.version_number, kv.status, kv.score, kv.evaluation::text",
                (rs, row) -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("versionNumber", rs.getInt(1));
                    node.put("status", rs.getString(2));
                    if (rs.getObject(3) != null) {
                        node.put("score", rs.getDouble(3));
                    }
                    node.put("evaluationMethod", method(rs.getString(4)));
                    node.put("documentCount", rs.getInt(5));
                    ObjectNode length = node.putObject("contentLength");
                    length.put("average", rs.getInt(6));
                    length.put("min", rs.getInt(7));
                    length.put("max", rs.getInt(8));
                    ObjectNode coverage = node.putObject("fieldCoverage");
                    coverage.put("description", rs.getInt(9));
                    coverage.put("image", rs.getInt(10));
                    coverage.put("eventDate", rs.getInt(11));
                    coverage.put("category", rs.getInt(12));
                    coverage.put("sourceUrl", rs.getInt(13));
                    return node;
                },
                versionId);
        return rows.isEmpty()
                ? error("VERSION_NOT_FOUND", "그 버전을 찾지 못했습니다.") : rows.get(0);
    }

    private JsonNode scoreQuestions(UUID versionId) {
        if (!inTourScope(versionId)) {
            return outOfScope();
        }
        JsonNode set = confirmedSet(versionId);
        if (set == null) {
            return error("NO_CONFIRMED_SET", "확정된 골든 질문 세트가 없습니다.");
        }
        Map<String, DocumentRef> present = documentsOf(versionId);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("setVersion", set.path("setVersion").asInt(1));
        ArrayNode failures = objectMapper.createArrayNode();
        int total = 0;
        int found = 0;
        int excluded = 0;
        for (JsonNode question : set.path("questions")) {
            String expected = question.path("expectedExternalDocumentId").asText("");
            String text = question.path("question").asText("");
            if (expected.isBlank() || text.isBlank()) {
                continue;
            }
            total++;
            DocumentRef reference = present.get(expected);
            if (reference == null) {
                // 정답 문서가 이 버전에 없으면 채점 대상이 아니다(BuildEvaluation과 같은 규칙).
                excluded++;
                continue;
            }
            int rank = rankOf(versionId, reference.documentId(), text);
            if (rank >= 1 && rank <= HIT_DEPTH) {
                found++;
                continue;
            }
            if (failures.size() < MAX_FAILURES) {
                ObjectNode failure = failures.addObject();
                failure.put("id", question.path("id").asText(""));
                failure.put("question", text);
                failure.put("expectedDocumentId", expected);
                failure.put("expectedTitle", reference.title());
                failure.put("rank", rank);
            }
        }
        result.put("totalQuestions", total);
        result.put("foundInTop5", found);
        result.put("notFound", total - found - excluded);
        result.put("excluded", excluded);
        result.set("failures", failures);
        result.put("truncated", total - found - excluded > failures.size());
        return result;
    }

    private JsonNode inspectDocuments(UUID versionId, List<String> documentIds) {
        if (!inTourScope(versionId)) {
            return outOfScope();
        }
        if (documentIds.isEmpty()) {
            return error("INVALID_ARGUMENTS", "document_ids가 비어 있습니다.");
        }
        List<String> wanted = documentIds.stream().distinct().limit(MAX_DOCUMENTS).toList();
        String placeholders = String.join(",", wanted.stream().map(id -> "?").toList());
        Object[] parameters = new Object[wanted.size() + 1];
        parameters[0] = versionId;
        for (int index = 0; index < wanted.size(); index++) {
            parameters[index + 1] = wanted.get(index);
        }

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode documents = result.putArray("documents");
        List<String> seen = new ArrayList<>();
        boolean[] truncated = {false};
        jdbc.query(
                "SELECT external_document_id, title, content, image_url, event_start_date "
                        + "FROM app.source_document WHERE knowledge_version_id = ? "
                        + "AND external_document_id IN (" + placeholders + ")",
                rs -> {
                    String content = rs.getString(3) == null ? "" : rs.getString(3);
                    ObjectNode node = documents.addObject();
                    node.put("externalDocumentId", rs.getString(1));
                    node.put("title", rs.getString(2));
                    node.put("contentLength", content.length());
                    if (content.length() > MAX_DOCUMENT_CHARACTERS) {
                        content = content.substring(0, MAX_DOCUMENT_CHARACTERS);
                        truncated[0] = true;
                    }
                    node.put("content", content);
                    node.put("hasDescription", content.contains("[개요]"));
                    node.put("hasImage", rs.getString(4) != null);
                    node.put("hasEventDate", rs.getObject(5) != null);
                    seen.add(rs.getString(1));
                },
                parameters);
        ArrayNode notFound = result.putArray("notFound");
        wanted.stream().filter(id -> !seen.contains(id)).forEach(notFound::add);
        result.put("truncated", truncated[0]);
        return result;
    }

    /** 이 버전이 관광 지식베이스 소속인가. 모든 도구의 첫 관문이다. */
    private boolean inTourScope(UUID versionId) {
        Integer matched = jdbc.queryForObject(
                "SELECT count(*) FROM app.knowledge_version kv "
                        + "JOIN app.knowledge_base kb ON kb.knowledge_base_id = kv.knowledge_base_id "
                        + "WHERE kv.knowledge_version_id = ? AND kb.name = ?",
                Integer.class, versionId, TOUR_KNOWLEDGE_BASE);
        return matched != null && matched > 0;
    }

    /** 확정된 세트만 읽는다. DRAFT는 어떤 점수에도 쓰이지 않는다는 규칙을 여기서도 지킨다. */
    private JsonNode confirmedSet(UUID versionId) {
        List<String> rows = jdbc.query(
                "SELECT kb.evaluation_question_set::text FROM app.knowledge_base kb "
                        + "JOIN app.knowledge_version kv "
                        + "ON kv.knowledge_base_id = kb.knowledge_base_id "
                        + "WHERE kv.knowledge_version_id = ? "
                        + "AND kb.evaluation_question_set->>'status' = 'CONFIRMED'",
                (rs, row) -> rs.getString(1), versionId);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(rows.get(0));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return null;
        }
    }

    private Map<String, DocumentRef> documentsOf(UUID versionId) {
        Map<String, DocumentRef> documents = new LinkedHashMap<>();
        // 람다 본문이 값을 돌려주면 ResultSetExtractor와 RowCallbackHandler 양쪽에 맞아
        // 호출이 모호해진다. 인자 타입을 적어 행 단위 처리로 못 박는다.
        jdbc.query(
                "SELECT external_document_id, source_document_id, title "
                        + "FROM app.source_document WHERE knowledge_version_id = ?",
                (java.sql.ResultSet rs) -> {
                    documents.put(rs.getString(1),
                            new DocumentRef(rs.getObject(2, UUID.class), rs.getString(3)));
                },
                versionId);
        return documents;
    }

    /**
     * 상위 {@code RANK_DEPTH}건에서 정답 문서의 1-기반 순위. 없으면 0.
     *
     * <p>빌드의 채점과 <b>같은 쿼리·같은 접기 규칙</b>을 쓴다. 다르게 재면 진단이 가리키는
     * 숫자와 화면의 점수가 어긋나 관리자가 둘 중 무엇을 믿어야 할지 모르게 된다.
     */
    private int rankOf(UUID versionId, UUID expectedDocumentId, String query) {
        List<UUID> found = jdbc.query(
                "SELECT dc.source_document_id FROM app.document_chunk dc "
                        + "WHERE dc.knowledge_version_id = ? AND dc.embedding IS NOT NULL "
                        + "ORDER BY dc.embedding <=> ?::vector, dc.document_chunk_id LIMIT ?",
                (rs, row) -> rs.getObject(1, UUID.class),
                versionId, embeddings.queryVector(query), RANK_DEPTH);
        int rank = found.stream().distinct().toList().indexOf(expectedDocumentId);
        return rank < 0 ? 0 : rank + 1;
    }

    private static String method(String evaluation) {
        if (evaluation == null || !evaluation.contains("\"method\"")) {
            return "NONE";
        }
        return evaluation.contains("GOLDEN_QUESTION") ? "GOLDEN_QUESTION" : "TITLE_SELF_RETRIEVAL";
    }

    private static UUID versionId(JsonNode arguments) {
        String raw = arguments.path("knowledge_version_id").asText("");
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("knowledge_version_id가 UUID가 아닙니다.");
        }
    }

    private static List<String> documentIds(JsonNode arguments) {
        List<String> ids = new ArrayList<>();
        for (JsonNode id : arguments.path("document_ids")) {
            String value = id.asText("").strip();
            if (!value.isEmpty()) {
                ids.add(value);
            }
        }
        return ids;
    }

    private JsonNode outOfScope() {
        return error("OUT_OF_SCOPE", "이 도구는 관광 포털 지식만 조회합니다.");
    }

    private JsonNode error(String code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        return node;
    }

    private record DocumentRef(UUID documentId, String title) { }
}
