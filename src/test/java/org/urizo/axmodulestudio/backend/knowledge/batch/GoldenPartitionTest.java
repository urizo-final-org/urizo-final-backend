package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.batch.ProductBatchService.DocRef;
import org.urizo.axmodulestudio.backend.knowledge.batch.ProductBatchService.GoldenQuestion;
import org.urizo.axmodulestudio.backend.knowledge.batch.ProductBatchService.Partition;

/**
 * 제외 동결 규칙(AXMS-AI02-020). partition은 순위 정보를 받지 않는다 — 시그니처가
 * 존재 여부만 받으므로 "성적을 보고 문항을 빼는" 코드는 여기서 작성될 수 없다.
 * 이 테스트는 그 규칙의 두 축을 고정한다: 부재만 제외 사유다, 수정은 기록일 뿐이다.
 */
class GoldenPartitionTest {

    private static final UUID DOCUMENT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static GoldenQuestion question(String id, String expected, String digest) {
        return new GoldenQuestion(id, "받을 수 있는 지원금이 있나요?", expected, digest);
    }

    @Test
    void aMissingExpectedDocumentIsExcludedWithItsReason() {
        Partition partition = ProductBatchService.partition(
                List.of(question("q01", "PBLN_A", "d1"), question("q02", "PBLN_GONE", "d2")),
                Map.of("PBLN_A", new DocRef(DOCUMENT, "d1")));

        assertThat(partition.askable()).extracting(GoldenQuestion::id).containsExactly("q01");
        assertThat(partition.excluded()).hasSize(1);
        assertThat(partition.excluded().get(0).id()).isEqualTo("q02");
        assertThat(partition.excluded().get(0).reason()).isEqualTo("DOCUMENT_MISSING");
    }

    /** 내용이 바뀐 문서는 여전히 정답이다. 빼면 "수정된 문서를 잘 찾는지"를 영영 못 잰다. */
    @Test
    void aModifiedDocumentStaysInTheExam() {
        Partition partition = ProductBatchService.partition(
                List.of(question("q01", "PBLN_A", "old-digest")),
                Map.of("PBLN_A", new DocRef(DOCUMENT, "new-digest")));

        assertThat(partition.askable()).hasSize(1);
        assertThat(partition.excluded()).isEmpty();
        assertThat(partition.modifiedCount()).isEqualTo(1);
    }

    @Test
    void everyDocumentGoneLeavesNothingAskable() {
        Partition partition = ProductBatchService.partition(
                List.of(question("q01", "PBLN_X", "d"), question("q02", "PBLN_Y", "d")),
                Map.of());

        assertThat(partition.askable()).isEmpty();
        assertThat(partition.excluded()).hasSize(2);
    }
}
