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

    @Test
    void carriesCategoryCodeAndLabelForLaterFiltering() {
        ProductApiContract.PreviewDocument document = TourismSampleDocumentLoader.documents().get(0);

        assertThat(document.category()).hasSize(2);
        assertThat(String.join(",", document.category())).hasSizeLessThanOrEqualTo(200);
    }
}
