package org.urizo.axmodulestudio.backend.knowledge.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도구 인자 검증과 상한 상수(AXMS-AI02-027).
 *
 * <p>스코프 차단 자체는 DB를 봐야 해서 여기서 재지 않는다. 대신 <b>DB에 닿기 전에</b>
 * 걸러져야 하는 것들과, 프롬프트 크기를 예측 가능하게 묶는 상한들을 고정한다.
 */
class TourDiagnosisToolsScopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unknownToolIsRefusedWithoutTouchingTheDatabase() {
        // jdbc·embeddings가 null이어도 도달하지 않는다 — 이름 판정이 먼저다.
        TourDiagnosisTools tools = new TourDiagnosisTools(null, null, objectMapper);

        JsonNode result = tools.call("drop_everything", objectMapper.createObjectNode());

        assertThat(result.path("error").asText()).isEqualTo("UNKNOWN_TOOL");
    }

    @Test
    void malformedVersionIdIsRefusedWithoutTouchingTheDatabase() {
        TourDiagnosisTools tools = new TourDiagnosisTools(null, null, objectMapper);
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("knowledge_version_id", "중기부-아님");

        JsonNode result = tools.call(TourDiagnosisTools.VERSION_OVERVIEW, arguments);

        assertThat(result.path("error").asText()).isEqualTo("INVALID_ARGUMENTS");
    }

    /**
     * 원문·실패 문항 상한은 요청 크기(65,536자)를 지키는 유일한 장치다. 올리려면 그
     * 예산을 다시 계산해야 한다는 것을 실패로 알린다.
     */
    @Test
    void promptSizeBoundsStayWithinTheRequestBudget() {
        assertThat(TourDiagnosisTools.MAX_DOCUMENTS).isEqualTo(10);
        assertThat(TourDiagnosisTools.MAX_DOCUMENT_CHARACTERS).isEqualTo(600);
        assertThat(TourDiagnosisTools.MAX_FAILURES).isEqualTo(20);
        // 원문 2회 + 실패 목록이 최악으로 겹쳐도 요청 상한의 절반을 넘지 않아야 한다.
        int worstCase = 2 * TourDiagnosisTools.MAX_DOCUMENTS * TourDiagnosisTools.MAX_DOCUMENT_CHARACTERS
                + TourDiagnosisTools.MAX_FAILURES * 120;
        assertThat(worstCase).isLessThan(65_536 / 2);
    }
}
