package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.urizo.axmodulestudio.backend.knowledge.integration.TourismSampleDocumentLoader;

/**
 * axms-ai02-012 백필 검증.
 *
 * <p>DB를 띄우지 않고 "무엇을 보내는가"만 본다 — 실제 적용은 verify-core-migrations와
 * full 실행이 확인한다. 여기서 지키는 것은 두 가지다: 반복 실행이 안전할 것, 사진이 없는
 * 문서를 채우지 않을 것.
 */
class SourceDocumentPhotoBackfillRunnerTest {

    @Test
    void updatesOnlyTheDocumentsThatHaveASourcePhoto() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[0]);

        new SourceDocumentPhotoBackfillRunner(jdbc).run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> arguments = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), arguments.capture());
        long withPhoto = TourismSampleDocumentLoader.documents().stream()
                .filter(document -> document.imageUrl() != null).count();
        assertThat(arguments.getValue()).hasSize((int) withPhoto).hasSize(405);
        // 사진이 없는 문서(반도식당 등 95건)는 UPDATE 대상이 아니다 — null을 써 넣지 않는다.
        assertThat(arguments.getValue()).allSatisfy(row -> assertThat(row[0]).isNotNull());
    }

    /** 이미 값이 있는 행은 건너뛴다. 이 조건이 빠지면 반복 실행이 원천 갱신을 덮어쓴다. */
    @Test
    void skipsRowsThatAlreadyCarryAPhotoSoRerunsAreSafe() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[0]);

        new SourceDocumentPhotoBackfillRunner(jdbc).run(null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), anyList());
        assertThat(sql.getValue())
                .contains("UPDATE app.source_document SET image_url = ?")
                .contains("image_url IS NULL");
        // 본문·digest·임베딩은 건드리지 않는다 — 사진은 표시용 메타데이터일 뿐이다.
        assertThat(sql.getValue()).doesNotContain("content").doesNotContain("digest");
    }
}
