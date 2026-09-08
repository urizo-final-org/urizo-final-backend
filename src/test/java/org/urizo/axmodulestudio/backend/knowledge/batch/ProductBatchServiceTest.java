package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * axms-ai02-008 정적 헬퍼 검증.
 *
 * <p>B-1: 임베딩 입력은 본문에서 [주소]·[행사기간]·[전화]·[홈페이지] 줄만 뺀 문자열이다.
 * document_chunk.content와 content_digest는 이 필터와 무관하게 원문 그대로다.
 */
class ProductBatchServiceTest {

    private static final String FESTIVAL_CONTENT = """
            [분류] 축제/공연/행사
            [유형] 축제
            [이름] 안동국제탈춤페스티벌
            [주소] 경상북도 안동시 육사로 239
            [행사기간] 20261003 ~ 20261018
            [전화] 054-841-6398
            [홈페이지] http://www.maskdance.com
            [개요] 안동에서 열리는 탈춤 축제.""";

    @Test
    void embeddingInputDropsOnlyTheNonSemanticLines() {
        String input = ProductBatchService.embeddingInput(FESTIVAL_CONTENT);

        assertThat(input).isEqualTo("""
                [분류] 축제/공연/행사
                [유형] 축제
                [이름] 안동국제탈춤페스티벌
                [개요] 안동에서 열리는 탈춤 축제.""");
    }

    @Test
    void embeddingInputKeepsContentWithoutExcludedLabelsIdentical() {
        String content = "[분류] 관광지\n[이름] 속초 해수욕장\n[개요] 해수욕장이다.";

        assertThat(ProductBatchService.embeddingInput(content)).isEqualTo(content);
    }
}
