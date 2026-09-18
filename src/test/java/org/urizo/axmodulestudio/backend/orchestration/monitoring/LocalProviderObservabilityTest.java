package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalProviderObservabilityTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "AXMS_PROVIDER_VERIFY_DATABASE", matches = "axms_verify_archive_[a-z0-9_]+")
    void archiveDeduplicatesLocalCallsPreservesNullsAndPagesAtIdenticalTimes() throws Exception {
        String database = System.getenv("AXMS_PROVIDER_VERIFY_DATABASE");
        String password = Files.readString(Path.of(".local/secrets/ai_workspace_password")).trim();
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:15432/" + database, "ai_workspace", password);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var jdbc = new JdbcTemplate(ds);
            UUID profile = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO app.ai_profile_version (profile_version_id,profile_key,profile_version,snapshot_json)
                VALUES (?,'LLM_OPS',1,jsonb_build_object('contractVersion','1.0','profileVersionId',?::text,
                'profileKey','LLM_OPS','profileVersion',1))
                """, profile, profile);
            UUID job = UUID.randomUUID(), call = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO app.ai_job_node_occurrence (job_id,profile_version_id,pipeline_attempt,execution_attempt,
                node_id,node_sequence,trace_id,node_type,handler_key,status,started_at,last_reported_at)
                VALUES (?,?,1,1,'analyze',1,?,'agent','coding.analyze','COMPLETED','2026-09-16T01:00:00Z','2026-09-16T01:01:00Z')
                """, job,profile,UUID.randomUUID());
            jdbc.update("""
                INSERT INTO app.ai_job_model_call (call_id,job_id,profile_version_id,pipeline_attempt,execution_attempt,
                node_id,node_sequence,turn_id,provider,model,provider_attempt,status,started_at,finished_at,input_tokens)
                VALUES (?,?,?,1,1,'analyze',1,?,'OPENAI','test-model',1,'SUCCEEDED','2026-09-16T01:00:00Z','2026-09-16T01:00:01Z',100)
                """,call,job,profile,UUID.randomUUID());
            jdbc.update("""
                INSERT INTO app.ai_provider_observation_archive (observation_id,linked_call_id,job_id,model,level,started_at,input_tokens,output_tokens,cached_input_tokens)
                VALUES ('archive-match',?,?,'test-model','DEFAULT','2026-09-16T01:00:00Z',999,20,0),
                       ('archive-only',NULL,?,'test-model','DEFAULT','2026-09-16T01:00:00Z',200,NULL,NULL),
                       ('archive-failed',NULL,?,'test-model','ERROR','2026-09-16T01:00:00Z',NULL,NULL,NULL)
                """,call,job,job,job);
            var service = new LocalProviderObservability(jdbc);
            String from="2026-09-16T00:00:00Z", to="2026-09-17T00:00:00Z", id=job.toString();
            var metric=service.metrics(from,to,id).rows().get(0);
            assertThat(metric.observationCount()).isEqualTo(3);
            assertThat(metric.inputTokens()).isEqualTo(300);
            assertThat(metric.outputTokens()).isEqualTo(20);
            assertThat(metric.totalTokens()).isEqualTo(120);
            assertThat(metric.inputKnown()).isEqualTo(2);
            assertThat(metric.totalKnown()).isEqualTo(1);
            assertThat(service.tokenUsage(from,to,id).points().stream().mapToLong(LocalProviderObservability.Point::observationCount).sum()).isEqualTo(3);
            var ids = new HashSet<String>(); String cursor=null;
            do {
                var page=service.observations(from,to,id,cursor,1);
                assertThat(page.observations()).hasSize(1);
                assertThat(ids.add(page.observations().get(0).id())).isTrue();
                cursor=page.nextCursor();
            } while(cursor!=null && ids.size()<5);
            assertThat(ids).hasSize(3);
            assertThat(cursor).isNull();
            assertThat(service.observations(from,to,UUID.randomUUID().toString(),null,50).observations()).isEmpty();
            assertThat(service.tokenUsage("2026-09-15T00:00:00Z",from,id).points()).isEmpty();
        });
    }

    @Test void rejectsInvalidRangesAndCursorsBeforeAnyDatabaseRead() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new LocalProviderObservability(jdbc);
        for (String[] input : new String[][] {
                {"bad", "2026-09-18T00:00:00Z", null},
                {"2026-09-18T00:00:00Z", "2026-09-17T00:00:00Z", null},
                {"2026-08-01T00:00:00Z", "2026-09-18T00:00:00Z", null},
                {"2026-09-17T00:00:00Z", "2026-09-18T00:00:00Z", "1-1-1-1-1"}}) {
            assertThatThrownBy(() -> service.metrics(input[0], input[1], input[2])).isInstanceOf(IllegalArgumentException.class);
        }
        for (String cursor : new String[] {"-1", "0", "oops", "9223372036854775808"}) {
            assertThatThrownBy(() -> service.observations("2026-09-17T00:00:00Z", "2026-09-18T00:00:00Z", null, cursor, 50))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.observations("2026-09-17T00:00:00Z", "2026-09-18T00:00:00Z", null, null, 51))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    /** Opt-in SELECT-only integration with the existing local runtime role; cannot write any data. */
    @Test
    @EnabledIfEnvironmentVariable(named = "AXMS_VERIFY_LOCAL_PROVIDER_READS", matches = "true")
    void actualPostgresQueriesPreserveCountsNullsUtcBucketsAndPagination() throws Exception {
        String password = Files.readString(Path.of(".local/secrets/ai_workspace_password")).trim();
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:15432/ax_module_studio?options=-c%20default_transaction_read_only%3Don", "ai_workspace", password);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SHOW default_transaction_read_only", String.class)).isEqualTo("on");
        var service = new LocalProviderObservability(jdbc);
        String job = jdbc.queryForObject("SELECT job_id::text FROM app.ai_job_model_call ORDER BY call_order DESC LIMIT 1", String.class);
        Instant latest = jdbc.queryForObject("SELECT max(started_at) FROM app.ai_job_model_call", java.sql.Timestamp.class).toInstant();
        String from = latest.minusSeconds(3 * 86400).toString(), to = latest.plusSeconds(3600).toString();
        var metrics = service.metrics(from, to, job);
        long expected = jdbc.queryForObject("SELECT count(*) FROM app.ai_job_model_call WHERE job_id=? AND started_at>=? AND started_at<?", Long.class,
                UUID.fromString(job), java.sql.Timestamp.from(Instant.parse(from)), java.sql.Timestamp.from(Instant.parse(to)));
        assertThat(metrics.rows().stream().mapToLong(LocalProviderObservability.Metric::observationCount).sum()).isEqualTo(expected);
        assertThat(metrics.source()).isEqualTo("LOCAL_DB");
        assertThat(metrics.rows()).allSatisfy(row -> {
            assertThat(row.totalCost()).isNull();
            assertThat(row.inputKnown()).isLessThanOrEqualTo(row.observationCount());
            assertThat(row.totalKnown()).isLessThanOrEqualTo(Math.min(row.inputKnown(), row.outputKnown()));
            if (row.inputKnown() == 0) assertThat(row.inputTokens()).isNull();
        });
        var daily = service.tokenUsage(from, to, job);
        assertThat(daily.granularity()).isEqualTo("day");
        assertThat(daily.points()).allSatisfy(point -> assertThat(point.bucketStart().toString()).endsWith("T00:00:00Z"));
        assertThat(daily.points().stream().mapToLong(LocalProviderObservability.Point::observationCount).sum()).isEqualTo(expected);
        var hourly = service.tokenUsage(latest.minusSeconds(3600).toString(), to, job);
        assertThat(hourly.granularity()).isEqualTo("hour");
        assertThat(hourly.points()).allSatisfy(point -> assertThat(point.bucketStart().toString()).endsWith(":00:00Z"));
        var page = service.observations(from, to, null, null, 1);
        assertThat(page.observations()).hasSize(1);
        assertThat(page.nextCursor()).isNotNull();
        var next = service.observations(from, to, null, page.nextCursor(), 1);
        assertThat(next.observations().get(0).id()).isNotEqualTo(page.observations().get(0).id());
        var jobCalls = service.observations(from, to, job, null, 50);
        assertThat(jobCalls.observations()).allSatisfy(call -> assertThat(call.metadata().jobId()).isEqualTo(job));
        assertThat(new HashSet<>(jobCalls.observations().stream().map(c -> c.id()).toList())).hasSize(jobCalls.observations().size());
        assertThat(service.metrics(from, to, UUID.randomUUID().toString()).rows()).isEmpty();
    }
}
