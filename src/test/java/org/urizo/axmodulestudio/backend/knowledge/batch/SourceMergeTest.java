package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * BASE + OVERLAY 병합(AXMS-AI02-023). 여기가 틀리면 전체 관광 코퍼스에 행사 날짜가 붙지 않아
 * 만료 표시가 통째로 꺼지거나, 반대로 OVERLAY가 문서를 늘려 "전체 관광"이 두 코퍼스의
 * 합집합으로 변한다.
 */
class SourceMergeTest {

    private static final Instant OLD = Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant NEW = Instant.parse("2026-09-09T00:00:00Z");

    private static ProductApiContract.PreviewDocument document(
            String id, String content, Instant updatedAt, String image) {
        return new ProductApiContract.PreviewDocument(
                id, "제목 " + id, content, List.of("EV01"),
                URI.create("https://source.invalid/documents/" + id), updatedAt, image);
    }

    /** 관광지 문서에 축제 원천의 날짜 줄이 붙는다. */
    @Test
    void anOverlaySuppliesTheLabelsTheBaseDoesNotHave() {
        SourceMerge.Result result = SourceMerge.merge(
                List.of(document("3113671", "강릉커피축제\n[주소] 강원특별자치도 강릉시", OLD, null)),
                List.of(document(
                        "3113671",
                        "강릉커피축제\n[행사시작] 20261016\n[행사종료] 20261025", NEW, null)));

        assertThat(result.enriched()).isEqualTo(1);
        assertThat(result.documents().get(0).content()).isEqualTo(
                "강릉커피축제\n[주소] 강원특별자치도 강릉시\n[행사시작] 20261016\n[행사종료] 20261025");
        // 제목 줄은 라벨 줄이 아니라서 두 번 실리지 않는다.
        assertThat(result.documents().get(0).content().split("강릉커피축제", -1)).hasSize(2);
    }

    /** OVERLAY에만 있는 문서는 버린다 — BASE가 문서 집합을 정한다. */
    @Test
    void anOverlayNeverAddsADocument() {
        SourceMerge.Result result = SourceMerge.merge(
                List.of(document("1", "관광지\n[주소] 서울", OLD, null)),
                List.of(
                        document("1", "관광지\n[행사시작] 20260101\n[행사종료] 20260102", OLD, null),
                        document("999", "BASE에 없는 축제\n[행사시작] 20260201", NEW, null)));

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).documentId()).isEqualTo("1");
    }

    /** 겹치지 않는 문서는 원본 그대로다 — 관광지에는 행사 날짜가 없어야 한다. */
    @Test
    void aDocumentWithoutAMatchIsUntouched() {
        ProductApiContract.PreviewDocument base = document("2", "숙박\n[주소] 전주", OLD, null);

        SourceMerge.Result result = SourceMerge.merge(
                List.of(base), List.of(document("3", "다른 축제\n[행사시작] 20260301", NEW, null)));

        assertThat(result.enriched()).isZero();
        assertThat(result.documents().get(0)).isEqualTo(base);
    }

    /** 같은 라벨이 양쪽에 있으면 BASE가 이긴다. 주소가 두 줄이 되면 안 된다. */
    @Test
    void theBaseWinsOnALabelBothSourcesHave() {
        SourceMerge.Result result = SourceMerge.merge(
                List.of(document("4", "축제\n[주소] 기준 주소\n[전화] 02-000-0000", OLD, null)),
                List.of(document("4", "축제\n[주소] 보조 주소\n[행사시작] 20260401", NEW, null)));

        assertThat(result.documents().get(0).content())
                .isEqualTo("축제\n[주소] 기준 주소\n[전화] 02-000-0000\n[행사시작] 20260401");
    }

    /** 수집 시각은 둘 중 최신을 쓴다. 한쪽만 갱신돼도 변경 감지가 그 사실을 본다. */
    @Test
    void theNewerSourceTimestampWinsAndTheBaseImageStands() {
        SourceMerge.Result newer = SourceMerge.merge(
                List.of(document("5", "축제", OLD, "https://image.invalid/base.jpg")),
                List.of(document("5", "축제\n[행사시작] 20260501", NEW, "https://image.invalid/x.jpg")));

        assertThat(newer.documents().get(0).sourceUpdatedAt()).isEqualTo(NEW);
        assertThat(newer.documents().get(0).imageUrl()).isEqualTo("https://image.invalid/base.jpg");

        SourceMerge.Result older = SourceMerge.merge(
                List.of(document("6", "축제", NEW, null)),
                List.of(document("6", "축제\n[행사시작] 20260601", OLD, "https://image.invalid/o.jpg")));

        assertThat(older.documents().get(0).sourceUpdatedAt()).isEqualTo(NEW);
        // BASE가 이미지를 비웠을 때만 OVERLAY 이미지를 쓴다.
        assertThat(older.documents().get(0).imageUrl()).isEqualTo("https://image.invalid/o.jpg");
    }

    /** OVERLAY에 같은 문서가 두 번 오면 첫 건만 쓴다(페이지 경계에서 흔들리지 않게). */
    @Test
    void aDuplicateOverlayDocumentUsesTheFirstOne() {
        SourceMerge.Result result = SourceMerge.merge(
                List.of(document("7", "축제", OLD, null)),
                List.of(
                        document("7", "축제\n[행사시작] 20260701", OLD, null),
                        document("7", "축제\n[행사시작] 20260702", NEW, null)));

        assertThat(result.documents().get(0).content()).contains("20260701").doesNotContain("20260702");
    }
}
