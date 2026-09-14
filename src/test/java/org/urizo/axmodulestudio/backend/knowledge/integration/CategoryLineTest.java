package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 분류 코드를 사람 말로 바꿔 본문에 남기는 줄(AXMS-AI02-023).
 *
 * <p>이 줄이 없으면 "숙박"·"쇼핑" 같은 검색어가 본문 어디에도 없어 그 질문이 통째로 검색되지
 * 않는다. 관광 실측: 같은 500건에서 픽스처 코퍼스는 "숙박"이 83건, 코드만 실은 코퍼스는 9건.
 */
class CategoryLineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode mapping(ObjectNode categoryLine) {
        ObjectNode mapping = MAPPER.createObjectNode()
                .put("documentId", "$.contentid")
                .put("title", "$.title")
                .put("content", "$.title")
                .put("category", "$.lclsSystm2");
        if (categoryLine != null) {
            mapping.set("categoryLine", categoryLine);
        }
        return mapping;
    }

    private static ObjectNode codes(String label, String... pairs) {
        ObjectNode codes = MAPPER.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) {
            codes.put(pairs[index], pairs[index + 1]);
        }
        ObjectNode line = MAPPER.createObjectNode();
        if (label != null) {
            line.put("label", label);
        }
        line.set("codes", codes);
        return line;
    }

    private static ObjectNode item(String category) {
        ObjectNode item = MAPPER.createObjectNode()
                .put("contentid", "126508")
                .put("title", "가야호텔");
        if (category != null) {
            item.put("lclsSystm2", category);
        }
        return item;
    }

    @Test
    void aCodeBecomesTheWordPeopleSearchWith() {
        ProductApiContract.PreviewDocument document = ConnectorDocumentClient.document(
                item("AC03"), mapping(codes("분류", "AC", "숙박", "FD", "음식")));

        assertThat(document.content()).isEqualTo("가야호텔\n[분류] 숙박");
    }

    /** 더 구체적인 코드가 이긴다. 안 그러면 AC가 AC03을 덮어쓴다. */
    @Test
    void theLongestMatchingPrefixWins() {
        ProductApiContract.PreviewDocument document = ConnectorDocumentClient.document(
                item("AC03"), mapping(codes(null, "AC", "숙박", "AC03", "숙박 > 한옥")));

        assertThat(document.content()).isEqualTo("가야호텔\n[분류] 숙박 > 한옥");
    }

    @Test
    void anUnknownOrAbsentCodeAddsNoLine() {
        ObjectNode line = codes("분류", "AC", "숙박");

        assertThat(ConnectorDocumentClient.document(item("ZZ99"), mapping(line)).content())
                .isEqualTo("가야호텔");
        assertThat(ConnectorDocumentClient.document(item(null), mapping(line)).content())
                .isEqualTo("가야호텔");
        assertThat(ConnectorDocumentClient.document(item("AC03"), mapping(null)).content())
                .isEqualTo("가야호텔");
    }
}
