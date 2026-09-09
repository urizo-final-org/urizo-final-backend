package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 옛 마크다운 본문은 읽는 입구에서만 문서로 바뀐다.
     *
     * <p>이 변환 하나로 관리자 화면·공개 사이트·자연어 Snapshot이 모두 문서를 받는다.
     */
    @Test
    void turnsTheOldThreeMarkdownFormsIntoADocument() throws Exception {
        JsonNode document = MAPPER.readTree(ContentBody.toDocument(
                "## 회사 소개\n\n우리는 **바이오** 기업입니다.\n\n- 첫째\n- 둘째"));

        assertThat(document.path("type").asText()).isEqualTo("doc");
        JsonNode nodes = document.path("content");
        assertThat(nodes.get(0).path("type").asText()).isEqualTo("heading");
        assertThat(nodes.get(0).path("attrs").path("level").asInt()).isEqualTo(2);
        assertThat(nodes.get(1).path("type").asText()).isEqualTo("paragraph");
        assertThat(nodes.get(1).path("content").get(1).path("marks").get(0).path("type").asText())
                .isEqualTo("bold");
        // 연속된 목록 줄은 하나의 목록으로 묶는다.
        assertThat(nodes.get(2).path("type").asText()).isEqualTo("bulletList");
        assertThat(nodes.get(2).path("content")).hasSize(2);
        assertThat(nodes).hasSize(3);
    }

    @Test
    void leavesADocumentAloneSoConvertedRowsAreNotTouchedAgain() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}";

        assertThat(ContentBody.toDocument(document)).isEqualTo(document);
    }

    @Test
    void acceptsTheNodesTheRendererCanDraw() {
        String document = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"heading\",\"attrs\":{\"level\":3},\"content\":"
                + "[{\"type\":\"text\",\"text\":\"소개\"}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"본문\","
                + "\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"https://a.test\"}}]}]},"
                + "{\"type\":\"image\",\"attrs\":{\"src\":\"/api/site/images/12\",\"alt\":\"전경\"}}"
                + "]}";

        assertThat(ContentBody.problem(document)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "## 제목",
        "그냥 글자",
        "[]",
        "{\"type\":\"paragraph\"}",
        "{\"type\":\"doc\"}",
        "{\"type\":\"doc\",\"content\":[]}",
    })
    void refusesAnythingThatIsNotAFilledDocument(String body) {
        assertThat(ContentBody.problem(body)).isNotNull();
    }

    @Test
    void refusesANodeTheRendererCannotDraw() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"table\",\"content\":[]}]}";

        assertThat(ContentBody.problem(document)).contains("쓸 수 없는");
    }

    /** 문서 제목은 별도 필드다. 본문 제목은 2·3단계만 쓴다. */
    @Test
    void refusesAHeadingLevelTheSiteDoesNotRender() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\","
                + "\"attrs\":{\"level\":1},\"content\":[]}]}";

        assertThat(ContentBody.problem(document)).contains("2단계와 3단계");
    }

    /** 외부 주소를 열면 방문자 접속 기록이 남의 서버로 새어 나간다. */
    @Test
    void refusesAnImageThatIsNotStoredInThisCms() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"image\","
                + "\"attrs\":{\"src\":\"https://example.test/a.png\"}}]}";

        assertThat(ContentBody.problem(document)).contains("올린 것만");
    }

    @Test
    void refusesALinkThatIsNotHttpOrASitePath() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"클릭\",\"marks\":[{\"type\":\"link\","
                + "\"attrs\":{\"href\":\"javascript:alert(1)\"}}]}]}]}";

        assertThat(ContentBody.problem(document)).contains("링크 주소");
    }

    /** `strike`는 `AI05-017`에서 열렸다. 아직 렌더러가 그리지 못하는 것으로 바꿔 검사한다. */
    @Test
    void refusesAMarkTheRendererCannotDraw() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"본문\","
                + "\"marks\":[{\"type\":\"superscript\"}]}]}]}";

        assertThat(ContentBody.problem(document)).contains("서식");
    }

    /** `AI05-017`에서 연 두 서식. 편집기·서버·렌더러가 같은 목록을 봐야 한다. */
    @Test
    void acceptsStrikeAndUnderline() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"본문\","
                + "\"marks\":[{\"type\":\"strike\"},{\"type\":\"underline\"}]}]}]}";

        assertThat(ContentBody.problem(document)).isNull();
    }

    /** 인용문과 구분선. 속성이 없어 이름만 확인한다. */
    @Test
    void acceptsQuoteAndDivider() {
        String document = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"blockquote\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"인용\"}]}]},"
                + "{\"type\":\"horizontalRule\"}]}";

        assertThat(ContentBody.problem(document)).isNull();
    }

    /** 팔레트에 있는 색만 통과한다. 자유 입력을 열면 임의의 CSS가 본문에 들어온다. */
    @Test
    void acceptsPaletteColours() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"본문\",\"marks\":["
                + "{\"type\":\"textStyle\",\"attrs\":{\"color\":\"#c0392b\"}},"
                + "{\"type\":\"highlight\",\"attrs\":{\"color\":\"#fff3a3\"}}]}]}]}";

        assertThat(ContentBody.problem(document)).isNull();
    }

    @Test
    void refusesAColourOutsideThePalette() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"본문\",\"marks\":["
                + "{\"type\":\"textStyle\",\"attrs\":{\"color\":\"red\"}}]}]}]}";

        assertThat(ContentBody.problem(document)).contains("글자색");
    }

    /** 형광펜은 색이 있어야 한다. 색 없는 형광은 화면에 아무것도 남기지 않는다. */
    @Test
    void refusesAHighlightWithoutAColour() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"본문\",\"marks\":[{\"type\":\"highlight\"}]}]}]}";

        assertThat(ContentBody.problem(document)).contains("형광펜");
    }

    /** 인라인 코드는 열지 않았다. 이 사이트 컨텐츠에 쓸 일이 없어 빼기로 했다. */
    @Test
    void refusesInlineCode() {
        String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"본문\",\"marks\":[{\"type\":\"code\"}]}]}]}";

        assertThat(ContentBody.problem(document)).contains("서식");
    }

    /**
     * 모델이 문서를 한 번 더 escape 해 보내는 일이 있다.
     *
     * <p>같은 요청인데 어떤 때는 제대로, 어떤 때는 이렇게 온다. 지시문으로 없앨 수 있는 종류가
     * 아니라 서버가 되돌린다. 되돌린 값이 그대로 저장되어야 DB에 깨진 본문이 남지 않는다.
     */
    @Test
    void unescapesADocumentTheModelEscapedTwice() {
        String twice = "{\\\"type\\\":\\\"doc\\\",\\\"content\\\":[{\\\"type\\\":\\\"paragraph\\\","
                + "\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"안내\\\"}]}]}";

        assertThat(ContentBody.problem(twice)).isNull();
        assertThat(ContentBody.normalize(twice))
                .startsWith("{\"type\":\"doc\"")
                .contains("\"text\":\"안내\"")
                .doesNotContain("\\\"");
    }

    /** 되돌린 뒤에도 허용 부품 검사는 그대로 탄다. escape 한 겹이 가드레일을 통과시키면 안 된다. */
    @Test
    void stillRefusesAForbiddenNodeInsideATwiceEscapedDocument() {
        String twice = "{\\\"type\\\":\\\"doc\\\",\\\"content\\\":"
                + "[{\\\"type\\\":\\\"table\\\",\\\"content\\\":[]}]}";

        assertThat(ContentBody.problem(twice)).contains("쓸 수 없는");
    }

    @Test
    void leavesSomethingThatIsNotADocumentAloneWhenNormalising() {
        assertThat(ContentBody.normalize("## 제목")).isEqualTo("## 제목");
    }

    /** 변환한 문서는 그대로 저장 검사를 통과해야 한다. 옛 본문이 열자마자 막히면 안 된다. */
    @Test
    void keepsConvertedMarkdownAcceptableToTheSaveCheck() {
        String converted = ContentBody.toDocument(
                "## 회사 소개\n\n우리는 **바이오** 기업입니다.\n\n- 첫째");

        assertThat(ContentBody.problem(converted)).isNull();
    }
}
