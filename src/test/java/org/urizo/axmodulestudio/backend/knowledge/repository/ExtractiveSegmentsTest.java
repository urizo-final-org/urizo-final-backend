package org.urizo.axmodulestudio.backend.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 추출형 답변의 문장 경계(AXMS-AI02-025). 여기가 틀리면 답변이 끝맺음 없이 잘린 채 화면에
 * 나간다 — 사람에게는 "말하다 만 챗봇"으로 읽힌다.
 */
class ExtractiveSegmentsTest {

    /** 공고 본문은 줄을 고정 폭으로 접어 저장돼 있다. 실측 그대로의 모양이다. */
    private static final String WRAPPED = """
            [부산] 남구 제2회 청년 창업 아이디어 경진대회 참가자 모집 공고
            [신청기간] 20260901 ~ 20260930
            초기 창업 아이디어를 가진 청년들이 아이디어를 실험하고 다듬을 수
            있는 기회를 제공하고자 제2회 부산 남구 청년 창업 아이디어 경진대회를
            개최합니다.
            선정된 팀에게는 사업화 자금을 지원합니다.""";

    @Test
    void aSentenceWrappedAcrossLinesStaysOneSentence() {
        List<String> segments = RagStore.segments(WRAPPED);

        assertThat(segments).containsExactly(
                "초기 창업 아이디어를 가진 청년들이 아이디어를 실험하고 다듬을 수 있는 기회를"
                        + " 제공하고자 제2회 부산 남구 청년 창업 아이디어 경진대회를 개최합니다.",
                "선정된 팀에게는 사업화 자금을 지원합니다.");
        // 어느 조각도 말하다 만 채로 끝나지 않는다.
        assertThat(segments).allSatisfy(segment -> assertThat(segment).endsWith("."));
    }

    /** 라벨 줄은 앞 문장에 딸려 붙지 않는다 — 새 항목의 시작이다. */
    @Test
    void aLabelLineIsNeverGluedToTheSentenceBeforeIt() {
        List<String> segments = RagStore.segments(
                "온고을공예방\n[분류] 쇼핑\n[주소] 전북 전주시\n[개요]\n한지 공예를 체험할 수 있다.");

        assertThat(segments).containsExactly("온고을공예방", "한지 공예를 체험할 수 있다.");
    }

    /** 이미 문장부호로 끝나는 줄은 그대로 나뉜다 — 예전 동작이 바뀌지 않는다. */
    @Test
    void linesThatAlreadyEndSentencesAreUnchanged() {
        assertThat(RagStore.segments("첫 문장이다.\n두 번째 문장이다.\n세 번째 문장이다."))
                .containsExactly("첫 문장이다.", "두 번째 문장이다.", "세 번째 문장이다.");
    }

    /** 한 줄에 두 문장이 있으면 문장부호로 나뉜다. */
    @Test
    void twoSentencesOnOneLineStillSplit() {
        assertThat(RagStore.segments("앞 문장이다. 뒤 문장이다."))
                .containsExactly("앞 문장이다.", "뒤 문장이다.");
    }
}
