package org.urizo.axmodulestudio.backend.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 후속 질문(AI02-003)의 검색 텍스트 조립을 지키는 테스트.
 *
 * <p>지키는 것은 하나다 — <b>직전 질문은 임베딩 입력에만 얹힌다.</b> 이 결합이 근거
 * 필터나 문장 추출로 새 나가면 이번 질문과 무관한 문서가 근거로 올라오므로,
 * {@code query()}는 두 곳에 계속 {@code request.query()}를 그대로 넘긴다.
 */
class RagStoreFollowUpQueryTest {

    @Test
    void aFirstTurnEmbedsTheQuestionAlone() {
        assertThat(RagStore.searchText(null, "전주 한옥스테이 추천해줘"))
                .isEqualTo("전주 한옥스테이 추천해줘");
        assertThat(RagStore.searchText("   ", "전주 한옥스테이 추천해줘"))
                .isEqualTo("전주 한옥스테이 추천해줘");
    }

    /** 대명사만 남은 후속 질문은 그 자체로 어떤 문서와도 가깝지 않다. */
    @Test
    void aFollowUpEmbedsThePreviousQuestionFirst() {
        assertThat(RagStore.searchText("전주 한옥스테이 추천해줘", "거기 주차 되나요?"))
                .isEqualTo("전주 한옥스테이 추천해줘 거기 주차 되나요?");
    }
}
