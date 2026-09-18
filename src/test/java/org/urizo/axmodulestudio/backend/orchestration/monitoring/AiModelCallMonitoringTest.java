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
    @Test void storesNullableUsageAndBodyFreeProcessingInTheSameActualCall() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        var monitor = new AiModelCallMonitoring(jdbc, Clock.systemUTC());
        var decision = new org.urizo.axmodulestudio.backend.integration.ai.observability.InputOptimizationScope.Decision(
                "tool-call", "search_code", "RTK", "selected", 6000, 4000, true);
        UUID call;
        try (var stage = ModelObservationScope.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "code");
             var turn = ModelObservationScope.openTurn(UUID.randomUUID(), 1);
             var input = new org.urizo.axmodulestudio.backend.integration.ai.observability.InputOptimizationScope(true, false, java.util.List.of(decision))) {
            call = monitor.started(ModelProvider.GOOGLE_GENAI, "test-model", 1);
        }
        assertThat(call).isNotNull();
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INSERT INTO app.ai_job_model_call"), args.capture());
        var metadata = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) args.getValue()[6]);
        assertThat(metadata.path("rtkEnabled").asBoolean()).isTrue();
        assertThat(metadata.path("decisions").get(0).path("beforeBytes").asInt()).isEqualTo(6000);
        monitor.usage(call, new org.urizo.axmodulestudio.backend.integration.ai.observability.ProviderTokenUsage(100, 20, null));
        org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET input_tokens"),
                org.mockito.ArgumentMatchers.eq(100), org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(call));
    }

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
