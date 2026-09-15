package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자르기 규칙(AXMS-AI02-018). 이 클래스가 지키는 것은 하나다 —
 * <b>어떤 전략이 와도 원문이 사라지지 않는다.</b> 청크가 근거로 인용되므로 잘린 조각의
 * 합이 원문을 덮지 못하면 답변이 근거 없는 문장을 갖게 된다.
 */
class ChunkSplitTest {

    @Test
    void wholeDocumentStrategyKeepsOneChunk() {
        String content = "가".repeat(5_000);

        assertThat(ProductBatchService.split(content, ChunkingStrategy.WHOLE_DOCUMENT))
                .containsExactly(content);
    }

    @Test
    void contentShorterThanTheLimitStaysWhole() {
        ChunkingStrategy strategy = new ChunkingStrategy(1_000, 100, "짧은 공고 위주.");

        assertThat(ProductBatchService.split("짧은 본문", strategy)).containsExactly("짧은 본문");
    }

    /** 문단 경계를 우선한다 — 상한에서 기계적으로 끊으면 문장이 반토막 난다. */
    @Test
    void splitsOnParagraphBoundariesWhenItCan() {
        String content = "가".repeat(300) + "\n\n" + "나".repeat(300) + "\n\n" + "다".repeat(300);
        ChunkingStrategy strategy = new ChunkingStrategy(700, 0, "문단이 뚜렷하다.");

        List<String> pieces = ProductBatchService.split(content, strategy);

        assertThat(pieces).hasSizeGreaterThan(1);
        assertThat(pieces).allSatisfy(piece -> assertThat(piece.length()).isLessThanOrEqualTo(700));
        // 경계가 문단이므로 한 조각 안에서 글자가 섞이지 않는다.
        assertThat(pieces.get(0)).doesNotContain("다");
    }

    /** 문단 하나가 상한보다 길면 그때만 상한에서 끊는다. 무한 루프에 빠지지 않아야 한다. */
    @Test
    void splitsInsideAParagraphThatExceedsTheLimit() {
        String content = "가".repeat(2_500);
        ChunkingStrategy strategy = new ChunkingStrategy(500, 0, "단일 문단이 길다.");

        List<String> pieces = ProductBatchService.split(content, strategy);

        assertThat(pieces).hasSizeGreaterThanOrEqualTo(5);
        assertThat(pieces).allSatisfy(piece -> assertThat(piece.length()).isLessThanOrEqualTo(500));
        assertThat(String.join("", pieces)).isEqualTo(content);
    }

    /** 겹침은 앞 조각의 꼬리를 다음 조각 머리에 싣는다. */
    @Test
    void overlapCarriesTheTailOfThePreviousPiece() {
        String content = "가".repeat(300) + "\n\n" + "나".repeat(300);
        ChunkingStrategy strategy = new ChunkingStrategy(400, 50, "경계 문장을 살린다.");

        List<String> pieces = ProductBatchService.split(content, strategy);

        assertThat(pieces).hasSizeGreaterThan(1);
        assertThat(pieces.get(1)).startsWith("가".repeat(50));
    }

    /** 잘린 조각을 이어 붙이면 원문이 남아 있어야 한다(겹침 없는 경우). */
    @Test
    void piecesCoverTheWholeContentWithoutOverlap() {
        String content = ("공고 본문 " + "가".repeat(200)) + "\n\n" + ("두 번째 " + "나".repeat(200));
        ChunkingStrategy strategy = new ChunkingStrategy(250, 0, "겹침 없음.");

        String rejoined = String.join("", ProductBatchService.split(content, strategy))
                .replaceAll("\\s", "");

        assertThat(rejoined).isEqualTo(content.replaceAll("\\s", ""));
    }

    /** 범위 밖 값은 쓰지 않는다 — 잘라서 쓰면 모델의 오해가 조용히 반영된다. */
    @Test
    void outOfRangeStrategiesAreNotUsable() {
        assertThat(new ChunkingStrategy(100, 0, "너무 작다.").usable()).isFalse();
        assertThat(new ChunkingStrategy(9_000, 0, "너무 크다.").usable()).isFalse();
        assertThat(new ChunkingStrategy(1_000, 600, "겹침이 절반을 넘는다.").usable()).isFalse();
        assertThat(new ChunkingStrategy(1_000, 200, "적당하다.").usable()).isTrue();
    }
}
