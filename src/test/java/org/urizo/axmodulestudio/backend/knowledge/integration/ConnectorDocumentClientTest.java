package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 중기부 지원사업 공고 API의 실제 응답 모양으로 매핑을 고정한다.
 *
 * <p>본문 값은 2026-09-09 실측 응답에서 가져왔다. 봉투가 {@code $.response.body.items.item}로
 * 한 겹 더 있어 dot-path 해석이 계약이다.
 */
class ConnectorDocumentClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MAPPING = """
            {
              "documentId": "$.pblancId",
              "title": "$.pblancNm",
              "content": "$.bsnsSumryCn",
              "category": "$.pldirSportRealmLclasCodeNm",
              "sourceUpdatedAt": "$.updtPnttm",
              "sourceUrl": "$.pblancUrl"
            }
            """;

    private static final String ITEM = """
            {
              "pblancNm": "[전남광주] 진도군 2026년 착한가격업소 신규지정 추가 모집 공고",
              "pblancUrl": "https://www.bizinfo.go.kr/sii/siia/selectSIIA200Detail.do?pblancId=PBLN_126291",
              "pblancId": "PBLN_000000000126291",
              "bsnsSumryCn": "<p>저렴한 가격과 친절한 서비스로 지역 물가안정에 기여하는 업소를 발굴한다.</p>",
              "pldirSportRealmLclasCodeNm": "경영",
              "updtPnttm": "2026-09-08 15:18:00"
            }
            """;

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void readsItemsThroughTheNestedEnvelope() {
        JsonNode payload = json("""
                {"response": {"header": {"resultCode": "00"},
                 "body": {"totalCount": 1549, "items": {"item": [%s, %s]}}}}
                """.formatted(ITEM, ITEM));

        assertThat(ConnectorDocumentClient.items(payload, "$.response.body.items.item")).hasSize(2);
        assertThat(ConnectorDocumentClient.at(payload, "$.response.body.totalCount").asInt())
                .isEqualTo(1549);
    }

    @Test
    void treatsASingleObjectAsOneItem() {
        // 결과가 1건일 때 배열이 아니라 객체로 오는 원천이 있다. 0건으로 읽으면 마지막
        // 페이지가 조용히 사라진다.
        JsonNode payload = json("""
                {"response": {"body": {"items": {"item": %s}}}}
                """.formatted(ITEM));

        assertThat(ConnectorDocumentClient.items(payload, "$.response.body.items.item")).hasSize(1);
    }

    @Test
    void returnsNoItemsWhenThePathIsAbsent() {
        JsonNode payload = json("{\"response\": {\"body\": {}}}");

        assertThat(ConnectorDocumentClient.items(payload, "$.response.body.items.item")).isEmpty();
    }

    @Test
    void mapsTheAnnouncementOntoAPreviewDocument() {
        ProductApiContract.PreviewDocument document =
                ConnectorDocumentClient.document(json(ITEM), json(MAPPING));

        assertThat(document.documentId()).isEqualTo("PBLN_000000000126291");
        assertThat(document.title()).startsWith("[전남광주] 진도군");
        assertThat(document.content()).contains("물가안정");
        assertThat(document.category()).containsExactly("경영");
        assertThat(document.sourceUrl().toString()).startsWith("https://www.bizinfo.go.kr/");
        assertThat(document.sourceUpdatedAt()).isEqualTo(
                LocalDateTime.of(2026, 9, 8, 15, 18).atZone(ZoneId.of("Asia/Seoul")).toInstant());
    }

    @Test
    void rejectsNonHttpsSourceUrlBeforeItReachesTheCheckConstraint() {
        JsonNode item = json(ITEM.replace("https://www.bizinfo", "http://www.bizinfo"));

        assertThatThrownBy(() -> ConnectorDocumentClient.document(item, json(MAPPING)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-HTTPS");
    }

    @Test
    void failsWhenAMappedRequiredFieldIsMissing() {
        JsonNode item = json("{\"pblancId\": \"PBLN_1\", \"pblancNm\": \"제목\"}");

        assertThatThrownBy(() -> ConnectorDocumentClient.document(item, json(MAPPING)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content");
    }

    @Test
    void acceptsBothDomainTimestampShapes() {
        Instant sme = ConnectorDocumentClient.timestamp("2026-09-08 15:18:00", "PBLN_1");
        Instant tourism = ConnectorDocumentClient.timestamp("20260908151800", "2570435");

        assertThat(sme).isEqualTo(tourism);
        assertThat(ConnectorDocumentClient.timestamp("2026-09-08T06:18:00Z", "PBLN_1"))
                .isEqualTo(sme);
    }

    @Test
    void failsOnAnUnreadableTimestampInsteadOfGuessing() {
        assertThatThrownBy(() -> ConnectorDocumentClient.timestamp("예산 소진시까지", "PBLN_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PBLN_1");
    }

    /**
     * 2026-09-09 실측 응답에서 가져온 본문이다. 태그·엔티티·꺾쇠 인용이 한 문서에 다 들어 있다.
     * 기대값은 시험지 코퍼스(`api-test/sme/sme_documents_500.json`)에 실제로 적재된 문자열이다.
     */
    @Test
    void cleansRealSourceBodyTheSameWayTheCorpusBuilderDoes() {
        String raw = "<p>한국무역협회는 울산광역시와 공동으로 다음과 같이 "
                + "&lt;2026 베트남 국제 산업기계 및 장비박람회(VINAMAC EXPO 2026&gt;에 울산관을 "
                + "운영할 예정이오니, 관심 있으신 울산 기업 분들의 많은 신청을 바랍니다.</p>"
                + "<p><br></p><p style=\"line-height: 1.8;\">☞&nbsp;울산 소재 기업</p>";

        assertThat(ConnectorDocumentClient.clean(raw)).isEqualTo(
                "한국무역협회는 울산광역시와 공동으로 다음과 같이 "
                        + "<2026 베트남 국제 산업기계 및 장비박람회(VINAMAC EXPO 2026>에 울산관을 "
                        + "운영할 예정이오니, 관심 있으신 울산 기업 분들의 많은 신청을 바랍니다.\n\n"
                        + "☞ 울산 소재 기업");
    }

    @Test
    void removesTagsBeforeRestoringEntitiesSoQuotedTitlesSurvive() {
        // 순서를 뒤집으면 &lt;2026 예술산업보증&gt;이 태그로 보여 본문이 통째로 사라진다.
        assertThat(ConnectorDocumentClient.clean("<p>&lt;2026 예술산업보증&gt; 안내</p>"))
                .isEqualTo("<2026 예술산업보증> 안내");
    }

    @Test
    void restoresEntitiesInOnedPassSoEscapedEntitiesStayEscaped() {
        assertThat(ConnectorDocumentClient.clean("A &amp;lt; B")).isEqualTo("A &lt; B");
    }

    @Test
    void restoresNumericReferences() {
        assertThat(ConnectorDocumentClient.clean("&#x41;&#66;")).isEqualTo("AB");
    }

    @Test
    void keepsUnparsableNumericReferencesAsWrittenInsteadOfFailingTheJob() {
        // 원천 한 건의 이상한 참조가 수집 전체를 죽이면 안 된다. 셋 다 정규식에는 걸린다.
        assertThat(ConnectorDocumentClient.clean("A &#abc; B")).isEqualTo("A &#abc; B");
        assertThat(ConnectorDocumentClient.clean("A &#99999999999; B"))
                .isEqualTo("A &#99999999999; B");
        assertThat(ConnectorDocumentClient.clean("A &#x110000; B")).isEqualTo("A &#x110000; B");
    }

    @Test
    void appendsMappedMetadataAsLabelledLinesInMappingOrder() {
        String mapping = """
                {
                  "documentId": "$.pblancId", "title": "$.pblancNm", "content": "$.bsnsSumryCn",
                  "metadata": {
                    "신청기간": "$.reqstBeginEndDe",
                    "신청방법": "$.reqstMthPapersCn",
                    "문의처": "$.refrncNm",
                    "없는칸": "$.absent"
                  }
                }
                """;
        JsonNode item = json("""
                {
                  "pblancId": "PBLN_1", "pblancNm": "제목",
                  "bsnsSumryCn": "<p>본문입니다.</p>",
                  "reqstBeginEndDe": "2026-09-07 ~ 2026-10-02",
                  "reqstMthPapersCn": "방문 접수",
                  "refrncNm": "진도군청 061-540-6407"
                }
                """);

        assertThat(ConnectorDocumentClient.document(item, json(mapping)).content()).isEqualTo("""
                본문입니다.
                [신청기간] 2026-09-07 ~ 2026-10-02
                [신청방법] 방문 접수
                [문의처] 진도군청 061-540-6407""");
    }

    @Test
    void failsWhenTheBodyIsOnlyMarkup() {
        // source_document가 빈 본문을 CHECK로 막는다. 여기서 걸러야 원인이 드러난다.
        JsonNode item = json(ITEM.replace(
                "<p>저렴한 가격과 친절한 서비스로 지역 물가안정에 기여하는 업소를 발굴한다.</p>",
                "<p><br></p>"));

        assertThatThrownBy(() -> ConnectorDocumentClient.document(item, json(MAPPING)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no content after cleaning");
    }

    @Test
    void keepsCategoryEmptyRatherThanBlankWhenTheSourceOmitsIt() {
        JsonNode item = json(ITEM.replace("\"pldirSportRealmLclasCodeNm\": \"경영\"",
                "\"pldirSportRealmLclasCodeNm\": \"\""));

        assertThat(ConnectorDocumentClient.document(item, json(MAPPING)).category()).isEmpty();
    }
}
