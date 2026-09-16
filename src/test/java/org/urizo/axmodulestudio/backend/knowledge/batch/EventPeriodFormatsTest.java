package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * 행사 기간 파싱(AXMS-AI02-023). 두 형식을 다 받아야 한다 — 픽스처는 한 줄 쌍을,
 * 커넥터 실수집은 필드당 한 줄씩(두 줄)을 만든다. 여기가 틀리면 종료 행사 표시 전체가
 * 조용히 꺼진다(중기부 event date 0/500이 그 사례였다).
 */
class EventPeriodFormatsTest {

    @Test
    void theFixtureSingleLineFormatStillParses() {
        ProductBatchService.EventPeriod period = ProductBatchService.eventPeriod(
                "[이름] 서천 축제\n[행사기간] 20260822 ~ 20260906\n[개요]\n본문");

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 9, 6));
    }

    @Test
    void theConnectorTwoLineFormatParses() {
        ProductBatchService.EventPeriod period = ProductBatchService.eventPeriod(
                "강릉커피축제\n[주소] 강원특별자치도 강릉시\n[행사시작] 20261016\n[행사종료] 20261025\n[전화] 033-000-0000");

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    /** 한 쪽만 있으면 기간이 아니다 — 반쪽 날짜로 종료 판정을 하면 안 된다. */
    @Test
    void aLoneStartOrEndLineIsNotAPeriod() {
        assertThat(ProductBatchService.eventPeriod("[행사시작] 20261016\n본문"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
        assertThat(ProductBatchService.eventPeriod("[행사종료] 20261025\n본문"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
    }

    @Test
    void malformedDatesFallToNoneInBothFormats() {
        assertThat(ProductBatchService.eventPeriod("[행사기간] 20261301 ~ 20261399"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
        assertThat(ProductBatchService.eventPeriod("[행사시작] 20261301\n[행사종료] 20261399"))
                .isEqualTo(ProductBatchService.EventPeriod.NONE);
    }
}
