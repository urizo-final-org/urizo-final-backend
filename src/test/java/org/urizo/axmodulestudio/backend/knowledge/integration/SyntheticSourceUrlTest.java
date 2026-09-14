package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 문서 URL이 없는 원천의 합성 주소(AXMS-AI02-023). source_url이 NOT NULL이라
 * 폴백이 없으면 URL 필드 없는 원천(TourAPI 축제 목록)은 수집 전체가 죽는다.
 */
class SyntheticSourceUrlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode mapping(boolean withSourceUrl) {
        ObjectNode mapping = MAPPER.createObjectNode()
                .put("documentId", "$.contentid")
                .put("title", "$.title")
                .put("content", "$.title");
        if (withSourceUrl) {
            mapping.put("sourceUrl", "$.firstimage");
        }
        return mapping;
    }

    private static ObjectNode item(String image) {
        ObjectNode item = MAPPER.createObjectNode()
                .put("contentid", "3113671")
                .put("title", "강릉커피축제");
        if (image != null) {
            item.put("firstimage", image);
        }
        return item;
    }

    @Test
    void aSourceWithoutAUrlFieldGetsASyntheticHttpsAddress() {
        ProductApiContract.PreviewDocument document =
                ConnectorDocumentClient.document(item(null), mapping(false));

        assertThat(document.sourceUrl().toString())
                .isEqualTo("https://source.invalid/documents/3113671");
    }

    /**
     * 공공 API는 값이 없는 칸을 빈 문자열로 돌려준다. 그대로 흘리면 image_url CHECK 위반이나
     * 날짜 파싱 실패로 적재가 죽는다 — 없는 값으로 취급해야 한다.
     */
    @Test
    void anEmptyStringFieldCountsAsAbsent() {
        ProductApiContract.PreviewDocument document =
                ConnectorDocumentClient.document(item(""), mapping(true));

        assertThat(document.sourceUrl().toString())
                .isEqualTo("https://source.invalid/documents/3113671");
    }

    @Test
    void aRealHttpsUrlIsKeptAndANonHttpsOneIsStillRejected() {
        assertThat(ConnectorDocumentClient.document(
                item("https://tong.visitkorea.or.kr/a.jpg"), mapping(true)).sourceUrl().toString())
                .isEqualTo("https://tong.visitkorea.or.kr/a.jpg");
        assertThatThrownBy(() -> ConnectorDocumentClient.document(
                item("http://insecure.example/a.jpg"), mapping(true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
