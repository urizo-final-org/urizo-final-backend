package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

class TourismSampleDocumentLoaderTest {

    @Test
    void loadsEveryDocumentWithoutCap() {
        List<ProductApiContract.PreviewDocument> documents = TourismSampleDocumentLoader.documents();

        // 오프라인 평가 하네스가 1문서=1청크 500개를 전제하므로 건수는 계약이다.
        assertThat(documents).hasSize(500);
        assertThat(TourismSampleDocumentLoader.totalCount()).isEqualTo(500);
    }

    @Test
    void keepsDocumentIdsUniqueForTheSourceDocumentConstraint() {
        Set<String> ids = TourismSampleDocumentLoader.documents().stream()
                .map(ProductApiContract.PreviewDocument::documentId)
                .collect(Collectors.toSet());

        assertThat(ids).hasSize(500);
    }

    @Test
    void synthesizesHttpsSourceUrlsSoTheCheckConstraintPasses() {
        assertThat(TourismSampleDocumentLoader.documents())
                .allSatisfy(document -> assertThat(document.sourceUrl().toString())
                        .startsWith("https://api-test.local/documents/"));
    }

    /**
     * 사진은 있는 문서에만 있다. 건수를 계약으로 박아 두면 fixture가 조용히 바뀌었을 때 드러난다.
     */
    @Test
    void carriesTheSourcePhotoOnlyForDocumentsThatHaveOne() {
        List<ProductApiContract.PreviewDocument> withPhoto = TourismSampleDocumentLoader.documents()
                .stream().filter(document -> document.imageUrl() != null).toList();

        assertThat(withPhoto).hasSize(405);
        // 스킴은 원천 값 그대로다 — http가 섞여 있고 https로 바꿔 적지 않는다.
        assertThat(withPhoto).allSatisfy(document -> assertThat(document.imageUrl())
                .matches("^https?://.+")
                .hasSizeLessThanOrEqualTo(500));
    }

    @Test
    void carriesCategoryCodeAndLabelForLaterFiltering() {
        ProductApiContract.PreviewDocument document = TourismSampleDocumentLoader.documents().get(0);

        assertThat(document.category()).hasSize(2);
        assertThat(String.join(",", document.category())).hasSizeLessThanOrEqualTo(200);
    }
}
