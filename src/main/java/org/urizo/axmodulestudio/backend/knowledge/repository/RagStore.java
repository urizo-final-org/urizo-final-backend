package org.urizo.axmodulestudio.backend.knowledge.repository;

import java.net.URI;
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
import org.urizo.axmodulestudio.backend.knowledge.integration.DeterministicConnectorFixture;

@Repository
@Profile("local-full")
public class RagStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProjectStore projects;
    private final KnowledgeStore knowledge;

    RagStore(
            JdbcTemplate productJdbcTemplate,
            Clock clock,
            ProjectStore projects,
            KnowledgeStore knowledge) {
        this.jdbc = productJdbcTemplate;
        this.clock = clock;
        this.projects = projects;
        this.knowledge = knowledge;
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
            UUID chatbotId, UUID traceId, ProductApiContract.RagQueryRequest request) {
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
        int topK = request.topK() == null ? 3 : request.topK();
        List<GroundingRow> rows = jdbc.query(
                "SELECT sd.external_document_id, sd.title, sd.source_url, dc.content, "
                        + "GREATEST(0, LEAST(1, 1 - (dc.embedding <=> ?::vector))) AS score "
                        + "FROM app.document_chunk dc JOIN app.source_document sd "
                        + "ON sd.source_document_id = dc.source_document_id "
                        + "WHERE dc.knowledge_version_id = ? AND dc.embedding IS NOT NULL "
                        + "ORDER BY dc.embedding <=> ?::vector, dc.document_chunk_id LIMIT ?",
                (rs, row) -> new GroundingRow(
                        rs.getString(1), rs.getString(2), URI.create(rs.getString(3)),
                        rs.getString(4), rs.getDouble(5)),
                DeterministicConnectorFixture.vector(request.query()), active.versionId(),
                DeterministicConnectorFixture.vector(request.query()), topK);
        List<GroundingRow> grounded = rows.stream()
                .filter(row -> DeterministicConnectorFixture.hasGroundingOverlap(
                        request.query(), row.content()))
                .toList();
        UUID conversationId = request.conversationId() == null
                ? UUID.randomUUID() : request.conversationId();
        Instant now = Instant.now(clock);
        if (grounded.isEmpty()) {
            return new ProductApiContract.RagQueryResponse(
                    version(), traceId, UUID.randomUUID(), conversationId,
                    "REFUSED", "활성 지식에서 답변을 뒷받침할 근거를 찾지 못했습니다.",
                    List.of(), active.versionId(), now);
        }
        GroundingRow first = grounded.get(0);
        List<ProductApiContract.Citation> citations = grounded.stream()
                .map(row -> new ProductApiContract.Citation(
                        row.documentId(), row.title(), row.sourceUrl(),
                        excerpt(row.content()), row.score()))
                .toList();
        return new ProductApiContract.RagQueryResponse(
                version(), traceId, UUID.randomUUID(), conversationId,
                "ANSWERED", "활성 지식의 근거에 따르면 " + first.content(),
                citations, active.versionId(), now);
    }

    private ProductApiContract.ChatbotResponse chatbot(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.ChatbotResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), instant(rs, 6));
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

    private record ActiveKnowledge(UUID versionId) {
    }

    private record GroundingRow(
            String documentId, String title, URI sourceUrl, String content, double score) {
    }
}
