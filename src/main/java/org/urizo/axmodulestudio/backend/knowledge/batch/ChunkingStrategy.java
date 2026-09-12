package org.urizo.axmodulestudio.backend.knowledge.batch;

/**
 * 한 Knowledge Version이 쓰는 청킹 규칙. 버전에 저장되어 재빌드 때 그대로 재사용된다.
 *
 * <p>LLM이 고르는 것은 이 <b>세 값뿐</b>이다. 자르는 일은 코드가 한다 — 모델이 본문을 다시
 * 쓰면 근거가 원문과 달라지고, 그 순간 인용이 거짓이 된다.
 *
 * @param maxCharacters 한 청크의 상한. 넘으면 문단 경계에서 나눈다.
 * @param overlapCharacters 앞 청크 꼬리를 다음 청크 머리에 겹쳐 싣는 길이. 경계에 걸친
 *                          문장이 어느 쪽에서도 온전하지 않게 되는 것을 막는다.
 * @param reason 모델이 적은 근거. 버전 비교에서 "왜 이 값이었나"를 사람이 읽는 유일한 자리다.
 */
public record ChunkingStrategy(int maxCharacters, int overlapCharacters, String reason) {

    /** 문서당 1청크. AI02-018 이전의 동작이며 LLM이 없거나 실패했을 때의 폴백이다. */
    public static final ChunkingStrategy WHOLE_DOCUMENT =
            new ChunkingStrategy(0, 0, "문서 전체를 한 청크로 둔다(LLM 전략 없음).");

    /**
     * 상한은 임베딩 모델이 한 번에 의미를 담는 길이와 검색 단위의 절충이다. 너무 작으면
     * 한 공고가 여러 조각으로 흩어져 Recall이 떨어지고, 너무 크면 한 청크가 여러 주제를
     * 담아 유사도가 흐려진다. 모델이 이 범위를 벗어나면 값을 자르지 않고 폴백한다 —
     * 범위 밖 값은 모델이 스키마를 오해했다는 신호이지 과감한 판단이 아니다.
     */
    static final int MIN_CHARACTERS = 200;
    static final int MAX_CHARACTERS = 4_000;

    public ChunkingStrategy {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Chunking strategy needs a reason.");
        }
    }

    /** 자르지 않는 전략인가. */
    public boolean splitsNothing() {
        return maxCharacters <= 0;
    }

    /**
     * 모델이 돌려준 값이 쓸 수 있는 범위인가. 겹침은 상한의 절반을 넘을 수 없다 —
     * 절반을 넘으면 다음 청크가 앞 청크를 대부분 복제해 색인만 부풀린다.
     */
    public boolean usable() {
        return maxCharacters >= MIN_CHARACTERS
                && maxCharacters <= MAX_CHARACTERS
                && overlapCharacters >= 0
                && overlapCharacters <= maxCharacters / 2;
    }
}
