package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;

class AiModelCallMonitoringTest {
    @Test
    void missingProvenanceDoesNotInventAnOccurrence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var monitor = new AiModelCallMonitoring(jdbc, Clock.systemUTC());
        assertThat(monitor.started(ModelProvider.OPENAI, "gpt-test", 1)).isNull();
        try (var scope = ModelObservationScope.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "analyze")) {
            assertThat(monitor.started(ModelProvider.OPENAI, "gpt-test", 1)).isNull();
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void nestedTurnInheritsAndRestoresStageProvenance() {
        UUID job = UUID.randomUUID(), trace = UUID.randomUUID(), profile = UUID.randomUUID(), turn = UUID.randomUUID();
        try (var stage = ModelObservationScope.open(job, trace, profile, "analyze")) {
            try (var inner = ModelObservationScope.open(null, trace, null, null);
                 var execution = ModelObservationScope.openTurn(turn, 2)) {
                assertThat(ModelObservationScope.current()).isEqualTo(new ModelObservationScope.Metadata(job, trace, profile, "analyze", turn, 2));
            }
            assertThat(ModelObservationScope.current().turnId()).isNull();
            assertThat(ModelObservationScope.current().nodeId()).isEqualTo("analyze");
        }
        assertThat(ModelObservationScope.current()).isNull();
    }

    @Test
    void unavailableLogHasExplicitEmptyStatus() {
        var monitor = new AiModelCallMonitoring(new DriverManagerDataSource("jdbc:no-such-driver:test"), Clock.systemUTC());
        assertThat(monitor.snapshot(UUID.randomUUID(), UUID.randomUUID())).isEqualTo(AiModelCallMonitoring.Snapshot.unavailable());
    }
}
