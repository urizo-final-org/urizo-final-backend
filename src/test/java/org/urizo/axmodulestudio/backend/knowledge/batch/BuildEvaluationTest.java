package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 지표 산술(AXMS-AI02-019). 틀려도 화면에는 그럴듯한 숫자가 나오므로 여기서 고정한다.
 */
class BuildEvaluationTest {

    @Test
    void everyQueryFindingItsDocumentFirstIsAPerfectScore() {
        BuildEvaluation evaluation = BuildEvaluation.of(List.of(1, 1, 1, 1));

        assertThat(evaluation.hit5()).isEqualTo(1.0);
        assertThat(evaluation.hit10()).isEqualTo(1.0);
        assertThat(evaluation.mrr10()).isEqualTo(1.0);
        assertThat(evaluation.score()).isEqualTo(100.0);
        assertThat(evaluation.sampleSize()).isEqualTo(4);
    }

    /** 상위 5건 밖은 Hit@5에서 빠지고, 10건 안이면 Hit@10과 MRR에는 남는다. */
    @Test
    void aRankBeyondFiveCountsForTenButNotForFive() {
        BuildEvaluation evaluation = BuildEvaluation.of(List.of(1, 7));

        assertThat(evaluation.hit5()).isEqualTo(0.5);
        assertThat(evaluation.hit10()).isEqualTo(1.0);
        // (1/1 + 1/7) / 2
        assertThat(evaluation.mrr10()).isCloseTo(0.5714, within(0.0001));
    }

    /** 못 찾은 질의는 0으로 들어온다 — 평균에서 빠지는 것이 아니라 0점으로 들어가야 한다. */
    @Test
    void aMissedQueryLowersEveryMetric() {
        BuildEvaluation evaluation = BuildEvaluation.of(List.of(1, 0));

        assertThat(evaluation.hit5()).isEqualTo(0.5);
        assertThat(evaluation.hit10()).isEqualTo(0.5);
        assertThat(evaluation.mrr10()).isEqualTo(0.5);
        assertThat(evaluation.score()).isEqualTo(50.0);
    }

    /** 아무것도 재지 못했으면 0이다. 빈 표본을 100점으로 돌려주면 빈 색인이 통과한다. */
    @Test
    void anEmptySampleIsNotAPass() {
        BuildEvaluation evaluation = BuildEvaluation.of(List.of());

        assertThat(evaluation.sampleSize()).isZero();
        assertThat(evaluation.score()).isZero();
    }

    @Test
    void carriesTheMethodSoTheScreenCanSayWhatWasMeasured() {
        assertThat(BuildEvaluation.of(List.of(1)).method())
                .isEqualTo(BuildEvaluation.TITLE_SELF_RETRIEVAL);
    }

    /** 골든 지표는 같은 산술에 세트 버전·동결된 제외·수정 기록을 더 싣는다(AI02-020). */
    @Test
    void goldenCarriesTheSetVersionAndTheFrozenExclusions() {
        BuildEvaluation evaluation = BuildEvaluation.golden(
                1, List.of(1, 2),
                List.of(new BuildEvaluation.ExcludedQuestion("q07", "DOCUMENT_MISSING")), 4);

        assertThat(evaluation.method()).isEqualTo(BuildEvaluation.GOLDEN_QUESTION);
        assertThat(evaluation.sampleSize()).isEqualTo(2);
        assertThat(evaluation.mrr10()).isEqualTo(0.75);
        assertThat(evaluation.setVersion()).isEqualTo(1);
        assertThat(evaluation.excluded()).hasSize(1);
        assertThat(evaluation.modifiedCount()).isEqualTo(4);
    }

    /** 제목 자가검색 JSON은 예전 모양 그대로여야 한다 — 새 필드가 null로도 끼면 안 된다. */
    @Test
    void titleJsonKeepsItsOriginalShape() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(BuildEvaluation.of(List.of(1)));

        assertThat(json).contains("TITLE_SELF_RETRIEVAL")
                .doesNotContain("setVersion").doesNotContain("excluded")
                .doesNotContain("modifiedCount");
    }

    @Test
    void goldenJsonCarriesTheNewFields() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(BuildEvaluation.golden(
                        2, List.of(1),
                        List.of(new BuildEvaluation.ExcludedQuestion("q03", "DOCUMENT_MISSING")), 0));

        assertThat(json).contains("GOLDEN_QUESTION").contains("\"setVersion\":2")
                .contains("\"q03\"").contains("DOCUMENT_MISSING").contains("\"modifiedCount\":0");
    }
}
