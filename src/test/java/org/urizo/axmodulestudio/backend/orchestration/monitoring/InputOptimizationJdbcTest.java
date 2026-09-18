package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.observability.InputOptimizationScope;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ProviderTokenUsage;

/** Explicitly approved disposable DB only. Never accepts the application DB URL. */
@EnabledIfEnvironmentVariable(named = "AXMS_INPUT_HISTORY_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/axms_verify_ai06047_[a-f0-9]{32}")
class InputOptimizationJdbcTest {
    @Test void emptyAndDevUpgradePreserveUnknownValuesAndRuntimePrivileges() throws Exception {
        String url = System.getenv("AXMS_INPUT_HISTORY_TEST_URL");
        String ownerPassword = Files.readString(Path.of(".local/secrets/migration_owner_password")).trim();
        boolean upgrade = "true".equals(System.getenv("AXMS_INPUT_HISTORY_TEST_UPGRADE"));
        if (upgrade) Flyway.configure().dataSource(url, "migration_owner", ownerPassword)
                .target("20260914035606830").cleanDisabled(true).load().migrate();
        var flyway = Flyway.configure().dataSource(url, "migration_owner", ownerPassword).cleanDisabled(true).load();
        int migrations = flyway.migrate().migrationsExecuted;
        if (upgrade) assertThat(migrations).isEqualTo(1); else assertThat(migrations).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
        var owner = new JdbcTemplate(new DriverManagerDataSource(url, "migration_owner", ownerPassword));
        var runtime = new DriverManagerDataSource(url, "ai_workspace", Files.readString(Path.of(".local/secrets/ai_workspace_password")).trim());
        var jdbc = new JdbcTemplate(runtime);
        UUID profile = UUID.randomUUID(), job = UUID.randomUUID(), trace = UUID.randomUUID(), turn = UUID.randomUUID();
        owner.update("""
                INSERT INTO app.ai_profile_version(profile_version_id,profile_key,profile_version,status,snapshot_json)
                VALUES (?, 'LLM_OPS', 1, 'ACTIVE', jsonb_build_object('contractVersion','1.0',
                    'profileVersionId',?::text,'profileKey','LLM_OPS','profileVersion',1))
                """, profile, profile);
        jdbc.update("""
                INSERT INTO app.ai_job_monitoring_state(job_id,trace_id,profile_version_id,pipeline_attempt,execution_attempt,
                    monitor_status,current_node_id,current_node_sequence,current_node_type,current_handler_key,current_node_status,last_reported_at)
                VALUES (?,?,?,1,1,'RUNNING','code',1,'agent','coding.code','RUNNING',now())
                """, job, trace, profile);
        jdbc.update("""
                INSERT INTO app.ai_job_node_occurrence(job_id,profile_version_id,pipeline_attempt,execution_attempt,node_id,
                    node_sequence,trace_id,node_type,handler_key,status,started_at,last_reported_at)
                VALUES (?,?,1,1,'code',1,?,'agent','coding.code','RUNNING',now(),now())
                """, job, profile, trace);
        var monitor = new AiModelCallMonitoring(runtime, Clock.systemUTC());
        try (var stage = ModelObservationScope.open(job, trace, profile, "code");
             var execution = ModelObservationScope.openTurn(turn, 1)) {
            UUID old = monitor.started(ModelProvider.OPENAI, "fixture", 1);
            monitor.finished(old, null);
            assertThat(jdbc.queryForMap("SELECT input_tokens, output_tokens, cached_input_tokens, input_processing FROM app.ai_job_model_call WHERE call_id=?", old))
                    .allSatisfy((key, value) -> assertThat(value).isNull());
            try (var input = new InputOptimizationScope(true, false, List.of(new InputOptimizationScope.Decision(
                    "tool", "search_code", "RTK", "selected", 6000, 4000, true)))) {
                UUID current = monitor.started(ModelProvider.GOOGLE_GENAI, "fixture", 1);
                monitor.usage(current, new ProviderTokenUsage(100, 10, 40));
                monitor.finished(current, null);
                var row = jdbc.queryForMap("SELECT input_tokens, cached_input_tokens, input_processing->>'rtkEnabled' AS rtk FROM app.ai_job_model_call WHERE call_id=?", current);
                assertThat(row).containsEntry("input_tokens", 100L).containsEntry("cached_input_tokens", 40L).containsEntry("rtk", "true");
                assertThatThrownBy(() -> jdbc.update("UPDATE app.ai_job_model_call SET cached_input_tokens=101 WHERE call_id=?", current)).isInstanceOf(DataAccessException.class);
            }
        }
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE app.forbidden_input_history_probe(id int)")).isInstanceOf(DataAccessException.class);
        var history = new InputOptimizationHistory(jdbc, new ObjectMapper(), Clock.systemUTC());
        var now = java.time.Instant.now();
        // Execute the whole read query, including joins and grants, without inventing a domain Job.
        assertThat(history.list(now.minusSeconds(3600), now.plusSeconds(3600), null).jobs()).isEmpty();
        assertThat(owner.queryForObject("SELECT bool_and(success) FROM public.flyway_schema_history", Boolean.class)).isTrue();
    }
}
