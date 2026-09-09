package org.urizo.axmodulestudio.backend.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** eventStatus 값은 "ENDED"와 null 둘뿐이다. 오늘이 종료일이면 아직 진행 중이다. */
class RagStoreEventStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);

    @Test
    void pastEndDateIsEnded() {
        assertThat(RagStore.eventStatus(LocalDate.of(2026, 9, 8), TODAY)).isEqualTo("ENDED");
    }

    @Test
    void endDateTodayIsNotEnded() {
        assertThat(RagStore.eventStatus(TODAY, TODAY)).isNull();
    }

    @Test
    void futureEndDateIsNotEnded() {
        assertThat(RagStore.eventStatus(LocalDate.of(2026, 9, 10), TODAY)).isNull();
    }

    @Test
    void documentsWithoutAnEventEndDateHaveNoStatus() {
        assertThat(RagStore.eventStatus(null, TODAY)).isNull();
    }
}
