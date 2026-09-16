package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 빌드가 스스로 잰 검색 품질(AXMS-AI02-019 · 020).
 *
 * <p><b>무엇을 재는지 이름에 담는다.</b> {@code TITLE_SELF_RETRIEVAL}은 문서 제목으로
 * 검색해 그 문서가 상위에 돌아오는지 보는 방식이다. 시험지(사람이 만든 질문과 정답)가
 * 아니므로 "사용자 질문에 잘 답하는가"를 증명하지 않는다 — 증명하는 것은 <b>색인이
 * 검색 가능한 상태인가</b>다. 빈 임베딩·잘못된 청킹·차원 불일치는 여기서 다 걸린다.
 *
 * <p>{@code GOLDEN_QUESTION}은 확정·동결된 골든 질문 세트로 잰 값이다(AI02-020).
 * 같은 {@code setVersion}으로 잰 버전끼리만 점수를 비교할 수 있다 — 시험지가 다르면
 * 점수 차이는 시험지 차이일 수 있다.
 *
 * <p>이 구분을 흐리면 관리자가 "품질 검증됨"으로 읽는다. 화면 문구도 방식을 같이 적는다.
 *
 * <p>정답 문서가 질의당 하나이므로 Recall@K와 Hit@K가 같은 값이다. 둘 다 싣지 않고
 * Hit만 남긴다 — 같은 수를 두 이름으로 보이면 다른 것을 쟀다고 오해한다.
 *
 * @param sampleSize 실제로 질의한 문항 수(사전 제외 문항은 빠진 뒤의 수)
 * @param hit5 상위 5건 안에 정답 문서가 있던 비율
 * @param hit10 상위 10건 기준 같은 비율
 * @param mrr10 상위 10건에서 정답 문서 순위의 역수 평균. 1에 가까울수록 앞에 있다
 * @param setVersion 골든 세트 버전. 제목 자가검색에는 없다
 * @param excluded 채점 <b>전에</b> 확정된 제외 문항과 사유. 순위를 보고 뺀 문항은 존재할 수 없다
 * @param modifiedCount 정답 문서가 세트 생성 시점과 내용이 달라진 문항 수. 제외 사유가 아니라 기록이다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildEvaluation(
        String method, int sampleSize, double hit5, double hit10, double mrr10,
        Integer setVersion, List<ExcludedQuestion> excluded, Integer modifiedCount) {

    public static final String TITLE_SELF_RETRIEVAL = "TITLE_SELF_RETRIEVAL";
    public static final String GOLDEN_QUESTION = "GOLDEN_QUESTION";

    /** 표본 상한. 질의마다 임베딩 1회라 크게 잡으면 빌드가 길어진다(실측 76ms/건). */
    static final int MAX_SAMPLE = 50;

    /** 상위 몇 건까지 보는지. SQL LIMIT과 MRR 분모가 같은 값을 써야 한다. */
    static final int DEPTH = 10;

    /** 채점에서 빠진 문항 하나. 사유는 존재 검사 같은 객관 규칙의 이름만 허용된다. */
    public record ExcludedQuestion(String id, String reason) { }

    /**
     * 제목 자가검색 지표. 못 찾은 질의는 {@code 0}으로 들어온다.
     *
     * @param ranks 질의별 1-기반 순위. 상위 {@link #DEPTH}건 밖이면 0.
     */
    static BuildEvaluation of(List<Integer> ranks) {
        double[] metrics = metrics(ranks);
        return new BuildEvaluation(TITLE_SELF_RETRIEVAL, ranks.size(),
                metrics[0], metrics[1], metrics[2], null, null, null);
    }

    /** 골든 질문 지표. 제외 목록은 채점 전에 확정된 것만 받는다. */
    static BuildEvaluation golden(
            int setVersion, List<Integer> ranks, List<ExcludedQuestion> excluded, int modifiedCount) {
        double[] metrics = metrics(ranks);
        return new BuildEvaluation(GOLDEN_QUESTION, ranks.size(),
                metrics[0], metrics[1], metrics[2],
                setVersion, List.copyOf(excluded), modifiedCount);
    }

    private static double[] metrics(List<Integer> ranks) {
        if (ranks.isEmpty()) {
            return new double[] {0, 0, 0};
        }
        long hit5 = ranks.stream().filter(rank -> rank >= 1 && rank <= 5).count();
        long hit10 = ranks.stream().filter(rank -> rank >= 1 && rank <= DEPTH).count();
        double reciprocal = ranks.stream()
                .mapToDouble(rank -> rank >= 1 && rank <= DEPTH ? 1.0 / rank : 0.0)
                .sum();
        int size = ranks.size();
        return new double[] {
                round((double) hit5 / size), round((double) hit10 / size), round(reciprocal / size)};
    }

    /** 소수 넷째 자리. 0.9733처럼 읽히는 자리까지만 남긴다. */
    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /** {@code knowledge_version.score}(0~100)에 넣을 값. Hit@5를 대표 지표로 쓴다. */
    public double score() {
        return round(hit5 * 100);
    }
}
