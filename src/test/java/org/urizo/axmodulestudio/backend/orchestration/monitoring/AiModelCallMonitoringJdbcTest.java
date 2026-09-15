package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;

/** Run only against an explicitly created, disposable verification database. Never the product DB. */
@EnabledIfEnvironmentVariable(named = "AXMS_MODEL_MONITOR_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/axms_verify_ai06045_[a-f0-9]{32}")
class AiModelCallMonitoringJdbcTest {
    @Test
    void migrationAndRuntimeRolePreserveOccurrenceIsolationAndFallbackHistory() throws Exception {
        String url = System.getenv("AXMS_MODEL_MONITOR_TEST_URL");
        String ownerPassword = Files.readString(Path.of(".local/secrets/migration_owner_password")).trim();
        boolean upgrade = "true".equals(System.getenv("AXMS_MODEL_MONITOR_TEST_UPGRADE"));
        if (upgrade) {
            var old = Flyway.configure().dataSource(url, "migration_owner", ownerPassword).target("20260913145252641").cleanDisabled(true).load();
            old.migrate();
        }
        var flyway = Flyway.configure().dataSource(url, "migration_owner", ownerPassword).cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(upgrade ? 1 : 51);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();

        var owner = new JdbcTemplate(new DriverManagerDataSource(url, "migration_owner", ownerPassword));
        var dataSource = new DriverManagerDataSource(url, "ai_workspace", Files.readString(Path.of(".local/secrets/ai_workspace_password")).trim());
        var jdbc = new JdbcTemplate(dataSource);
        var monitor = new AiModelCallMonitoring(dataSource, Clock.systemUTC());
        UUID profile = UUID.randomUUID();
        owner.update("""
                INSERT INTO app.ai_profile_version(profile_version_id, profile_key, profile_version, status, snapshot_json)
                VALUES (?, 'LLM_OPS', 1, 'ACTIVE', jsonb_build_object('contractVersion', '1.0',
                    'profileVersionId', ?::text, 'profileKey', 'LLM_OPS', 'profileVersion', 1))
                """, profile, profile);
        UUID job = UUID.randomUUID(), trace = UUID.randomUUID(), turn = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.ai_job_monitoring_state(job_id, trace_id, profile_version_id, pipeline_attempt,
                    execution_attempt, monitor_status, current_node_id, current_node_sequence,
                    current_node_type, current_handler_key, current_node_status, last_reported_at)
                VALUES (?, ?, ?, 1, 1, 'RUNNING', 'analyze', 1, 'agent', 'coding.analyze', 'RUNNING', now())
                """, job, trace, profile);
        jdbc.update("""
                INSERT INTO app.ai_job_node_occurrence(job_id, profile_version_id, pipeline_attempt,
                    execution_attempt, node_id, node_sequence, trace_id, node_type, handler_key, status, started_at, last_reported_at)
                VALUES (?, ?, 1, 1, 'analyze', 1, ?, 'agent', 'coding.analyze', 'RUNNING', now(), now())
                """, job, profile, trace);
        var stateBefore = jdbc.queryForMap("SELECT * FROM app.ai_job_monitoring_state WHERE job_id = ?", job);
        var profileBefore = owner.queryForMap("SELECT * FROM app.ai_profile_version WHERE profile_version_id = ?", profile);
        try (var stage = ModelObservationScope.open(job, trace, profile, "analyze");
             var execution = ModelObservationScope.openTurn(turn, 1)) {
            UUID claude = monitor.started(ModelProvider.ANTHROPIC, "claude-test", 1);
            assertThat(claude).isNotNull();
            assertThat(monitor.snapshot(job, profile).calls().get(0).status()).isEqualTo("RUNNING");
            monitor.finished(claude, ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
            UUID gpt = monitor.started(ModelProvider.OPENAI, "gpt-test", 1);
            assertThat(gpt).isNotNull();
            monitor.finished(gpt, null);
            monitor.finished(claude, null); // A late duplicate cannot rewrite the original failure.
            try (var stale = ModelObservationScope.openTurn(UUID.randomUUID(), 2)) {
                assertThat(monitor.started(ModelProvider.GOOGLE_GENAI, "gemini-test", 1)).isNull();
            }
            try (var wrongTrace = ModelObservationScope.open(null, UUID.randomUUID(), null, null)) {
                assertThat(monitor.started(ModelProvider.OPENAI, "gpt-test", 1)).isNull();
            }
            try (var wrongNode = ModelObservationScope.open(null, null, null, "code")) {
                assertThat(monitor.started(ModelProvider.OPENAI, "gpt-test", 1)).isNull();
            }
        }
        var result = monitor.snapshot(job, profile);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.calls()).extracting(AiModelCallMonitoring.Call::provider).containsExactly("ANTHROPIC", "OPENAI");
        assertThat(result.calls()).extracting(AiModelCallMonitoring.Call::status).containsExactly("FAILED", "SUCCEEDED");
        assertThat(result.calls().get(0).errorCode()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(result.calls()).allSatisfy(c -> {
            assertThat(c.turnId()).isEqualTo(turn);
            assertThat(c.nodeSequence()).isEqualTo(1);
            assertThat(c.finishedAt()).isNotNull();
        });
        assertThat(jdbc.queryForMap("SELECT * FROM app.ai_job_monitoring_state WHERE job_id = ?", job)).isEqualTo(stateBefore);
        assertThat(owner.queryForMap("SELECT * FROM app.ai_profile_version WHERE profile_version_id = ?", profile)).isEqualTo(profileBefore);
        assertThat(monitor.snapshot(UUID.randomUUID(), profile).calls()).isEmpty();
        assertThat(monitor.snapshot(job, UUID.randomUUID()).calls()).isEmpty();
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE app.forbidden_monitor_probe(id int)")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app.ai_job_model_call SET error_code = NULL WHERE status = 'FAILED'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app.ai_job_model_call SET node_sequence = 999"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app.ai_job_model_call SET provider = 'OTHER'"))
                .isInstanceOf(DataAccessException.class);

        owner.update("""
                INSERT INTO app.ai_job_model_call(call_id,job_id,profile_version_id,pipeline_attempt,execution_attempt,
                    node_id,node_sequence,turn_id,provider,model,provider_attempt,status,started_at,finished_at)
                SELECT gen_random_uuid(), ?, ?, 1, 1, 'analyze', 1, ?, 'OPENAI', 'gpt-test', 1, 'SUCCEEDED', now(), now()
                FROM generate_series(1, 501)
                """, job, profile, turn);
        var capped = monitor.snapshot(job, profile);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.calls()).hasSize(500);
        assertThat(capped.calls()).extracting(AiModelCallMonitoring.Call::callOrder).isSorted();
    }
}
