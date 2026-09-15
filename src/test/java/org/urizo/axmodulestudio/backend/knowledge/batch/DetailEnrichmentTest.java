package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 상세 본문 보강의 쓰기/읽기 짝(AXMS-AI02-023 W3). 두 쪽이 어긋나면 캐시가 한 번도 맞지 않고
 * 재빌드마다 문서 수만큼 외부 호출을 다시 쓴다 — 오류 없이 요금만 나가는 종류의 고장이다.
 */
class DetailEnrichmentTest {

    private static final ProductApiContract.PreviewDocument DOCUMENT =
            new ProductApiContract.PreviewDocument(
                    "126508", "가야호텔", "가야호텔\n[주소] 경상북도 성주군", List.of("AC03"),
                    URI.create("https://source.invalid/documents/126508"),
                    Instant.parse("2026-07-02T00:00:00Z"), null);

    @Test
    void whatWeAppendIsWhatWeReadBack() {
        String text = "가야산 자락에 자리한 숙소로, 등산객이 많이 찾는다.";

        ProductApiContract.PreviewDocument enriched =
                ProductBatchService.withDetail(DOCUMENT, "개요", text);

        assertThat(enriched.content())
                .isEqualTo("가야호텔\n[주소] 경상북도 성주군\n[개요]\n" + text);
        assertThat(ProductBatchService.detailText(enriched.content(), "개요")).isEqualTo(text);
    }

    /** 픽스처 코퍼스(v1)도 같은 형태다 — 이전 버전에서 재사용이 되어야 한다. */
    @Test
    void theFixtureCorpusFormatIsReadTheSameWay() {
        String content = "[분류] 숙박 > 펜션/민박\n[이름] 대동고택\n[개요]\n한옥 독채 스테이입니다.";

        assertThat(ProductBatchService.detailText(content, "개요"))
                .isEqualTo("한옥 독채 스테이입니다.");
    }

    /** 보강되지 않은 문서는 캐시에 올리지 않는다. 빈 문자열을 올리면 영영 다시 안 받는다. */
    @Test
    void aDocumentWithoutTheLabelHasNoCachedText() {
        assertThat(ProductBatchService.detailText(DOCUMENT.content(), "개요")).isNull();
        // 라벨이 본문 첫 줄이면 앞 줄바꿈이 없으므로 우리가 쓴 형태가 아니다.
        assertThat(ProductBatchService.detailText("[개요]\n본문", "개요")).isNull();
    }

    /** 보강해도 행사 날짜 파싱은 그대로여야 한다 — 개요가 뒤에 붙는다고 만료가 꺼지면 안 된다. */
    @Test
    void eventDatesStillParseAfterEnrichment() {
        ProductApiContract.PreviewDocument festival = new ProductApiContract.PreviewDocument(
                "4090201", "가든 나이트 마켓",
                "가든 나이트 마켓\n[행사시작] 20260729\n[행사종료] 20260829", List.of("EV03"),
                URI.create("https://source.invalid/documents/4090201"),
                Instant.parse("2026-07-22T00:00:00Z"), null);

        String content = ProductBatchService
                .withDetail(festival, "개요", "야시장으로 열리는 행사다.").content();

        assertThat(ProductBatchService.eventPeriod(content).start())
                .isEqualTo(java.time.LocalDate.of(2026, 7, 29));
        assertThat(ProductBatchService.eventPeriod(content).end())
                .isEqualTo(java.time.LocalDate.of(2026, 8, 29));
    }
}
