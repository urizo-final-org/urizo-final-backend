package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InputOptimizationHistoryTest {
    @Test void rejectsUnboundedRangesBeforeQuerying() {
        var jdbc = mock(JdbcTemplate.class);
        var history = new InputOptimizationHistory(jdbc, new ObjectMapper(), Clock.systemUTC());
        var from = Instant.parse("2026-09-01T00:00:00Z");
        assertThatThrownBy(() -> history.list(from, from, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.list(from, from.plusSeconds(31 * 86400L + 1), null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
