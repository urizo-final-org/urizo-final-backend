package org.urizo.axmodulestudio.backend.knowledge.repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;
import org.urizo.axmodulestudio.backend.knowledge.integration.DeterministicConnectorFixture;
import org.urizo.axmodulestudio.backend.knowledge.integration.EmbeddingClient;

@Repository
@Profile("local-full")
public class RagStore {

    /** citations 노출 수. retrieval K(기본 10)와는 별개의 표시 결정이다. */
    private static final int CITATION_LIMIT = 3;

    /**
     * citations 표시용 score 하한 — 꼬리 절단 전용. 정답/오답 판별 장치가 아니다
     * (D3 실측 역전: 비정답 0.6143 > 정답 0.5943 — 값을 올려 정밀도를 노리지 말 것).
     * 하한 자체는 거절 기준이 아니지만, 적용 결과 citations가 0건이 되면 "인용 없는
     * 답변"이 성립하지 않으므로 REFUSED로 처리한다(query() 참조).
     */
    private static final double CITATION_SCORE_FLOOR = 0.52;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProjectStore projects;
    private final KnowledgeStore knowledge;
    private final EmbeddingClient embeddings;

    RagStore(
            JdbcTemplate productJdbcTemplate,
            Clock clock,
            ProjectStore projects,
            KnowledgeStore knowledge,
            EmbeddingClient embeddings) {
        this.jdbc = productJdbcTemplate;
        this.clock = clock;
        this.projects = projects;
        this.knowledge = knowledge;
        this.embeddings = embeddings;
    }

    public ProductApiContract.ChatbotResponse createChatbot(
            UUID projectId, UUID traceId, ProductApiContract.CreateChatbotRequest request) {
        ProductApiContract.KnowledgeBaseResponse knowledgeBase = knowledge.getKnowledgeBase(
                request.knowledgeBaseId(), traceId);
        if (!knowledgeBase.projectId().equals(projectId)) {
            throw conflict(
                    "PROJECT_SCOPE_MISMATCH",
                    "Chatbot and knowledge base must belong to one project.");
        }
        UUID chatbotId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                "INSERT INTO app.chatbot_config "
                        + "(chatbot_id, project_id, knowledge_base_id, name, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                chatbotId, projectId, request.knowledgeBaseId(), request.name().trim(),
                Timestamp.from(now), Timestamp.from(now));
        return new ProductApiContract.ChatbotResponse(
                version(), traceId, chatbotId, projectId, request.knowledgeBaseId(),
                request.name().trim(), "ACTIVE", now);
    }

    public List<ProductApiContract.ChatbotResponse> listChatbots(
            UUID projectId, UUID traceId) {
        projects.requireProject(projectId);
        return jdbc.query(
                "SELECT chatbot_id, project_id, knowledge_base_id, name, status, created_at "
                        + "FROM app.chatbot_config WHERE project_id = ? "
                        + "ORDER BY created_at, chatbot_id",
                (rs, row) -> chatbot(rs, traceId), projectId);
    }

    public ProductApiContract.ChatbotResponse getChatbot(UUID id, UUID traceId) {
        return one(jdbc.query(
                "SELECT chatbot_id, project_id, knowledge_base_id, name, status, created_at "
                        + "FROM app.chatbot_config WHERE chatbot_id = ?",
                (rs, row) -> chatbot(rs, traceId), id),
                "CHATBOT_NOT_FOUND", "Chatbot not found.");
    }

    public ProductApiContract.RagQueryResponse query(
            UUID chatbotId,
            UUID traceId,
            ProductApiContract.RagQueryRequest request,
            List<String> category,
            String previousQuery) {
        ActiveKnowledge active = one(jdbc.query(
                "SELECT kb.active_version_id FROM app.chatbot_config cb "
                        + "JOIN app.knowledge_base kb ON kb.knowledge_base_id = cb.knowledge_base_id "
                        + "WHERE cb.chatbot_id = ? AND cb.status = 'ACTIVE'",
                (rs, row) -> new ActiveKnowledge(rs.getObject(1, UUID.class)), chatbotId),
                "CHATBOT_NOT_FOUND", "Chatbot not found.");
        if (active.versionId() == null) {
            throw conflict(
                    "ACTIVE_KNOWLEDGE_REQUIRED", "The chatbot has no active knowledge version.");
        }
        // 기본 10: C유형 정답 수가 3을 넘는 문항이 있어 3은 구조적 상한이었다(X2, R@10 0.973).
        // 접미절단 필터(W3) 전제. 요청이 topK를 명시하면 그 값을 그대로 쓴다(@Max 20).
        int topK = request.topK() == null ? 10 : request.topK();
        // 질의 임베딩은 HTTP 호출이므로 한 번만 계산해 정렬과 점수 계산에 함께 쓴다.
        String queryVector = embeddings.queryVector(searchText(previousQuery, request.query()));
        // 탭 필터(F6)는 WHERE에 건다. ORDER BY·LIMIT보다 먼저 평가되므로 "필터 후 상위 K건"이
        // 성립한다. 조회 뒤 자바에서 거르면 K건이 탭 밖 문서로 채워져 결과가 비게 된다.
        List<String> prefixes = categoryPrefixes(category);
        List<Object> arguments = new ArrayList<>();
        arguments.add(queryVector);
        arguments.add(active.versionId());
        prefixes.forEach(prefix -> arguments.add(prefix + "%"));
        arguments.add(queryVector);
        arguments.add(topK);
        List<GroundingRow> rows = jdbc.query(
                "SELECT sd.external_document_id, sd.title, sd.source_url, dc.content, "
                        + "GREATEST(0, LEAST(1, 1 - (dc.embedding <=> ?::vector))) AS score, "
                        + "sd.category, sd.event_end_date, sd.image_url "
                        + "FROM app.document_chunk dc JOIN app.source_document sd "
                        + "ON sd.source_document_id = dc.source_document_id "
                        + "WHERE dc.knowledge_version_id = ? AND dc.embedding IS NOT NULL"
                        + categoryCondition(prefixes)
                        + " ORDER BY dc.embedding <=> ?::vector, dc.document_chunk_id LIMIT ?",
                (rs, row) -> new GroundingRow(
                        rs.getString(1), rs.getString(2), URI.create(rs.getString(3)),
                        rs.getString(4), rs.getDouble(5), rs.getString(6),
                        rs.getObject(7, LocalDate.class), rs.getString(8)),
                arguments.toArray());
        List<GroundingRow> grounded = rows.stream()
                .filter(row -> DeterministicConnectorFixture.hasGroundingOverlap(
                        request.query(), row.content()))
                .toList();
        UUID conversationId = request.conversationId() == null
                ? UUID.randomUUID() : request.conversationId();
        Instant now = Instant.now(clock);
        if (grounded.isEmpty()) {
            return refused(traceId, conversationId, active.versionId(), now);
        }
        // 노출 3건 + score 하한 0.52. 이 하한은 정답/오답 판별 장치가 아니라 꼬리 노이즈
        // 절단 전용이다 — 실측에서 비정답(0.6143)이 정답(0.5943)보다 높은 역전이 확인돼
        // 전역 하한으로는 정밀도를 얻을 수 없다. 이 값을 올려 정밀도를 노리지 말 것.
        List<GroundingRow> displayed = grounded.stream()
                .limit(CITATION_LIMIT)
                .filter(row -> row.score() >= CITATION_SCORE_FLOOR)
                .toList();
        // 인용 0건 ANSWERED는 성립하지 않는 상태다 — 근거 기반 답변인데 근거가 없다.
        // 하한 자체는 거절 기준이 아니지만, 그 결과 citations가 비면 REFUSED가 된다
        // (V1 실측: 코퍼스 밖 질의가 '반도체'→'반도' 절단 매칭으로 필터를 통과했으나
        // 전 행 score < 하한이었던 케이스). 필터 전원 탈락 조건의 대체가 아니라 추가다.
        if (displayed.isEmpty()) {
            return refused(traceId, conversationId, active.versionId(), now);
        }
        LocalDate today = LocalDate.now(clock);
        List<ProductApiContract.Citation> citations = displayed.stream()
                .map(row -> new ProductApiContract.Citation(
                        row.documentId(), row.title(), row.sourceUrl(),
                        excerpt(row.content()), row.score(), categoryLabel(row.category()),
                        eventStatus(row.eventEndDate(), today), row.eventEndDate(), row.imageUrl()))
                .toList();
        return new ProductApiContract.RagQueryResponse(
                version(), traceId, UUID.randomUUID(), conversationId,
                "ANSWERED", composeAnswer(request.query(), displayed),
                citations, active.versionId(), now);
    }

    private static ProductApiContract.RagQueryResponse refused(
            UUID traceId, UUID conversationId, UUID versionId, Instant now) {
        return new ProductApiContract.RagQueryResponse(
                version(), traceId, UUID.randomUUID(), conversationId,
                "REFUSED", "활성 지식에서 답변을 뒷받침할 근거를 찾지 못했습니다.",
                List.of(), versionId, now);
    }

    /**
     * 강등형 답변(D5 확정): LLM 없이, 근거 문서에서 질의 토큰이 포함된 문장을 그대로 뽑아
     * 구성한다. 문장 매칭은 W3 접미절단 로직(hasGroundingOverlap)을 토큰 단위로 재사용한다.
     *
     * <p>1위 문서에서 매칭 문장 최대 2개를 뽑고, 하나뿐이면 2위 문서의 최고 매칭 문장을
     * 보탠다. 매칭 문장이 없으면 1위 문서의 첫 문장으로 폴백한다. 답변의 각 줄은 근거
     * 문서 본문에 실제로 존재하는 문자열이다(생성·요약 없음).
     */
    private static String composeAnswer(String query, List<GroundingRow> rows) {
        List<String> tokens = DeterministicConnectorFixture.groundingTokens(query);
        List<String> sentences = new ArrayList<>();
        List<String> primary = matchingSegments(tokens, rows.get(0).content());
        if (primary.isEmpty()) {
            List<String> all = segments(rows.get(0).content());
            if (!all.isEmpty()) {
                sentences.add(all.get(0));
            }
        }
        else {
            sentences.addAll(primary.subList(0, Math.min(2, primary.size())));
        }
        if (sentences.size() < 2 && rows.size() > 1) {
            List<String> secondary = matchingSegments(tokens, rows.get(1).content());
            if (!secondary.isEmpty()) {
                sentences.add(secondary.get(0));
            }
        }
        return String.join("\n", sentences);
    }

    /** 매칭 토큰 수 내림차순, 동률이면 본문 등장 순. 매칭된 문장만 반환한다. */
    private static List<String> matchingSegments(List<String> tokens, String content) {
        record Scored(int count, int position, String text) {
        }
        List<String> all = segments(content);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String segment = all.get(i);
            int count = 0;
            for (String token : tokens) {
                if (DeterministicConnectorFixture.hasGroundingOverlap(token, segment)) {
                    count++;
                }
            }
            if (count > 0) {
                scored.add(new Scored(count, i, segment));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::count).reversed()
                .thenComparingInt(Scored::position));
        return scored.stream().map(Scored::text).toList();
    }

    /** 줄바꿈·문장부호 경계로 나누고, "[분류]" 같은 필드 라벨 줄은 제외한다. */
    private static List<String> segments(String content) {
        return Arrays.stream(content.split("(?<=[.!?])\\s+|\\R+"))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty() && !segment.startsWith("["))
                .toList();
    }

    private ProductApiContract.ChatbotResponse chatbot(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.ChatbotResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), instant(rs, 6));
    }

    /**
     * 검색 임베딩에 쓸 텍스트. 직전 질문이 있으면 앞에 붙인다 — "거기 주차 되나요?"처럼
     * 대명사만 남은 후속 질문은 그 자체로는 어떤 문서와도 가깝지 않다.
     *
     * <p>이 결합은 <b>임베딩에만</b> 쓴다. 근거 필터({@code hasGroundingOverlap})와 문장
     * 추출({@code composeAnswer})은 계속 현재 질문만 본다. 직전 질문의 토큰까지 통과시키면
     * 이번 질문과 무관한 문서가 근거로 올라오고 답변 문장도 이전 주제에서 뽑힌다.
     */
    static String searchText(String previousQuery, String query) {
        return previousQuery == null || previousQuery.isBlank()
                ? query : previousQuery + " " + query;
    }

    private static String excerpt(String content) {
        return content.length() <= 500 ? content : content.substring(0, 500);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
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

    /**
     * 탭 필터 값 정리. category_id 접두만 받는다(contenttypeid 사용 금지 — 함정 23).
     * null·빈 목록은 "전체" 탭이고 필터를 걸지 않는다.
     */
    static List<String> categoryPrefixes(List<String> category) {
        if (category == null) {
            return List.of();
        }
        return category.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    /** 접두 하나당 LIKE 하나. 값은 전부 바인딩 파라미터로 나가고 SQL에 끼워 넣지 않는다. */
    static String categoryCondition(List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return "";
        }
        return " AND (" + String.join(
                " OR ", Collections.nCopies(prefixes.size(), "sd.category LIKE ?")) + ")";
    }

    /**
     * source_document.category는 로더가 {@code "category_id,category_label"}로 결합해
     * 저장한다(TourismSampleDocumentLoader). 공개 응답에는 라벨만 싣는다.
     * 콤마가 없는 값(fixture의 policy·safety·tourism)은 그대로 둔다.
     */
    static String categoryLabel(String category) {
        if (category == null) {
            return null;
        }
        int separator = category.indexOf(',');
        return separator < 0 ? category : category.substring(separator + 1);
    }

    /**
     * 종료일이 있고 오늘(서버 기준)보다 과거면 "ENDED", 그 외에는 null. 값은 이 둘뿐이다.
     * 오늘이 종료일이면 아직 진행 중이므로 ENDED가 아니다.
     */
    static String eventStatus(LocalDate eventEndDate, LocalDate today) {
        return eventEndDate != null && eventEndDate.isBefore(today) ? "ENDED" : null;
    }

    private record ActiveKnowledge(UUID versionId) {
    }

    private record GroundingRow(
            String documentId, String title, URI sourceUrl, String content, double score,
            String category, LocalDate eventEndDate, String imageUrl) {
    }
}
