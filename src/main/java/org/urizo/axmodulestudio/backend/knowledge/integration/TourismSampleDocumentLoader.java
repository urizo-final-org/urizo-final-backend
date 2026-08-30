package org.urizo.axmodulestudio.backend.knowledge.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 임시. D3 커넥터 실구현 시 제거.
 *
 * <p>기수집한 관광 표본 문서를 Knowledge Build의 COLLECT 입력으로 제공한다.
 * COLLECT 단계가 커넥터 config를 문서 조회에 사용하지 않는 현재 구조에서, 실제 규모의
 * 문서를 빌드 경로에 태우기 위한 주입 지점이다. 커넥터가 실제로 원천을 조회하게 되면
 * 이 클래스와 리소스 파일을 함께 삭제한다.
 *
 * <p>원본 데이터의 홈페이지 URL은 {@code http://}가 섞여 있어 source_document의
 * {@code ^https://} CHECK를 통과하지 못한다. 따라서 {@code sourceUrl}은 합성 URL을 쓰고,
 * 원본 URL은 본문 {@code [홈페이지]} 줄에 남은 것을 그대로 보존한다.
 */
public final class TourismSampleDocumentLoader {

    private static final String RESOURCE = "knowledge/fixture/tourism-sample-documents-500.json";
    private static final String SOURCE_URL_PREFIX = "https://api-test.local/documents/";
    private static final DateTimeFormatter MODIFIED_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Seoul");

    private static final List<ProductApiContract.PreviewDocument> DOCUMENTS = load();

    private TourismSampleDocumentLoader() {
    }

    /** 표본 문서 전체. 캡을 두지 않는다. */
    public static List<ProductApiContract.PreviewDocument> documents() {
        return DOCUMENTS;
    }

    public static int totalCount() {
        return DOCUMENTS.size();
    }

    private static List<ProductApiContract.PreviewDocument> load() {
        ClassLoader loader = TourismSampleDocumentLoader.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Tourism sample document resource is missing: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(stream);
            if (!root.isArray() || root.isEmpty()) {
                throw new IllegalStateException("Tourism sample document resource must be a non-empty array.");
            }
            List<ProductApiContract.PreviewDocument> documents = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                documents.add(document(node));
            }
            return List.copyOf(documents);
        }
        catch (IOException failure) {
            throw new IllegalStateException("Tourism sample documents could not be read.", failure);
        }
    }

    private static ProductApiContract.PreviewDocument document(JsonNode node) {
        String documentId = text(node, "doc_id");
        return new ProductApiContract.PreviewDocument(
                documentId,
                text(node, "title"),
                text(node, "text"),
                List.of(text(node, "category_id"), text(node, "category_label")),
                URI.create(SOURCE_URL_PREFIX + documentId),
                updatedAt(node.path("metadata").path("modifiedtime").asText(""), documentId));
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Tourism sample document is missing '" + field + "'.");
        }
        return value;
    }

    private static Instant updatedAt(String value, String documentId) {
        try {
            return LocalDateTime.parse(value, MODIFIED_TIME).atZone(SOURCE_ZONE).toInstant();
        }
        catch (DateTimeParseException failure) {
            throw new IllegalStateException(
                    "Tourism sample document " + documentId + " has an unreadable modifiedtime.", failure);
        }
    }
}
