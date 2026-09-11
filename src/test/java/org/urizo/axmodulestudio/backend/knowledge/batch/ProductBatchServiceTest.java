package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.integration.TourismSampleDocumentLoader;

/**
 * axms-ai02-008 정적 헬퍼 검증.
 *
 * <p>B-1: 임베딩 입력은 본문에서 [주소]·[행사기간]·[전화]·[홈페이지] 줄만 뺀 문자열이다.
 * document_chunk.content와 content_digest는 이 필터와 무관하게 원문 그대로다.
 *
 * <p>B-2: 행사 기간 파서는 어떤 입력에도 예외를 던지지 않는다 — 실패는 전부 NONE이다.
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
    void parsesTheEventPeriodLine() {
        ProductBatchService.EventPeriod period =
                ProductBatchService.eventPeriod(FESTIVAL_CONTENT);

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void missingPeriodLineFallsToNone() {
        ProductBatchService.EventPeriod period = ProductBatchService.eventPeriod(
                "[분류] 관광지\n[이름] 속초 해수욕장\n[개요] 해수욕장이다.");

        assertThat(period.start()).isNull();
        assertThat(period.end()).isNull();
    }

    @Test
    void malformedPeriodLineFallsToNoneInsteadOfThrowing() {
        assertThat(ProductBatchService.eventPeriod("[행사기간] 2026.10.03 ~ 2026.10.18"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
        assertThat(ProductBatchService.eventPeriod("[행사기간] 20261003"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
        // 자릿수는 맞지만 달력에 없는 날짜 — 예외 대신 NONE.
        assertThat(ProductBatchService.eventPeriod("[행사기간] 20261399 ~ 20261405"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
    }

    /** 코퍼스의 [행사기간] 줄은 기계 생성이라 전건 파싱돼야 한다. 건수가 계약이다. */
    @Test
    void everyFixturePeriodLineParses() {
        List<ProductApiContract.PreviewDocument> withPeriodLine =
                TourismSampleDocumentLoader.documents().stream()
                        .filter(document -> document.content().contains("[행사기간]"))
                        .toList();

        assertThat(withPeriodLine).hasSize(23);
        assertThat(withPeriodLine).allSatisfy(document -> {
            ProductBatchService.EventPeriod period =
                    ProductBatchService.eventPeriod(document.content());
            assertThat(period.start()).isNotNull();
            assertThat(period.end()).isNotNull();
            assertThat(period.start()).isBeforeOrEqualTo(period.end());
        });
    }
}
