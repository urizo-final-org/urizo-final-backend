package org.urizo.axmodulestudio.backend.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * F6 탭 필터(S3)의 SQL 조립과 categoryLabel 분리를 지키는 테스트.
 *
 * <p>필터가 WHERE에 붙는지가 핵심이다. ORDER BY·LIMIT 뒤로 밀리거나 자바 쪽으로 옮겨가면
 * "필터 후 상위 K건"이 깨져 탭 결과가 비는 회귀가 난다.
 */
class RagStoreCategoryFilterTest {

    @Test
    void noCategoryMeansNoFilter() {
        assertThat(RagStore.categoryPrefixes(null)).isEmpty();
        assertThat(RagStore.categoryPrefixes(List.of())).isEmpty();
        assertThat(RagStore.categoryCondition(List.of())).isEmpty();
    }

    @Test
    void blankAndNullEntriesAreDropped() {
        assertThat(RagStore.categoryPrefixes(Arrays.asList("AC", null, "   ", " EX ")))
                .containsExactly("AC", "EX");
    }

    @Test
    void aMultiPrefixTabBecomesOneWhereClause() {
        assertThat(RagStore.categoryCondition(List.of("LS", "EX")))
                .isEqualTo(" AND (sd.category LIKE ? OR sd.category LIKE ?)");
    }

    @Test
    void labelDropsTheCategoryIdPrefix() {
        assertThat(RagStore.categoryLabel("FD01,음식 > 한식")).isEqualTo("음식 > 한식");
        assertThat(RagStore.categoryLabel("AC03,숙박 > 펜션/민박")).isEqualTo("숙박 > 펜션/민박");
        assertThat(RagStore.categoryLabel("tourism")).isEqualTo("tourism");
        assertThat(RagStore.categoryLabel(null)).isNull();
    }
}
