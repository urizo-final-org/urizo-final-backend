package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;

class NaturalCmsStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-31T16:59:12Z");
    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID JOB_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PREVIEW_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID CREDENTIAL_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RESULT_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String PREVIEW_HASH = "sha256:"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AUTHORIZATION = "Bearer natural-cms-service";
    private static final NaturalCmsContract.ResourceRef RESOURCE =
            new NaturalCmsContract.ResourceRef("CONTENT", "7");
    private static final AuthenticatedActor ACTOR =
            new AuthenticatedActor(ACTOR_ID, "cms-admin", AdminRole.GENERAL_ADMIN);

    @Test
    @SuppressWarnings("unchecked")
    void createPersistsTheJobAndStrictOutboxInTheSameCodingTransaction() throws Exception {
        Harness harness = new Harness();
        when(harness.jdbc.query(
                argThat(sql -> sql != null && sql.contains("FROM app.ai_profile_version")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID)))
                .thenReturn(List.of("NATURAL_CMS:ACTIVE"));
        when(harness.jdbc.query(
                argThat(sql -> sql != null && sql.contains("FROM app.natural_cms_job")),
                any(RowMapper.class),
                any(UUID.class)))
                .thenAnswer(invocation -> {
                    UUID jobId = invocation.getArgument(2);
                    return List.of(job(jobId, 1, 1, "ACTIVE", null, null));
                });

        NaturalCmsContract.JobResponse created = harness.store.create(
                ACTOR,
                TRACE_ID,
                new NaturalCmsContract.CreateJobRequest(
                        NaturalCmsContract.SCHEMA_VERSION,
                        PROFILE_VERSION_ID,
                        "Update the article",
                        RESOURCE));

        assertThat(harness.updates).hasSize(2);
        UpdateCall jobInsert = harness.updateContaining("INSERT INTO app.natural_cms_job");
        UpdateCall outboxInsert = harness.updateContaining(
                "INSERT INTO app.transactional_outbox");
        assertThat(harness.updates.get(0)).isSameAs(jobInsert);
        assertThat(harness.updates.get(1)).isSameAs(outboxInsert);
        assertThat(jobInsert.arguments()[0]).isEqualTo(created.jobId());
        assertThat(outboxInsert.arguments()[1]).isEqualTo(created.jobId());
        assertStrictNaturalCmsOutbox(outboxInsert, created.jobId(), 1, harness.objectMapper);
        harness.verifyCommittedCodingTransaction();
        verifyNoInteractions(harness.productJdbc, harness.productTransactionManager);
    }

    @ParameterizedTest
    @MethodSource("firstDecisions")
    @SuppressWarnings("unchecked")
    void firstDecisionUpdatesStateVersionAndReenqueuesTheSameJobInOneCodingTransaction(
            String decision,
            String feedback,
            int resultingPipelineAttempt) throws Exception {
        Harness harness = new Harness();
        NaturalCmsContract.JobResponse waiting = job(
                JOB_ID, 1, 1, "WAITING_APPROVAL", null, null);
        NaturalCmsContract.JobResponse decided = job(
                JOB_ID, resultingPipelineAttempt, 2, "WAITING_APPROVAL", decision, feedback);
        when(harness.jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.natural_cms_job")
                        && sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq(JOB_ID)))
                .thenReturn(List.of(waiting));
        when(harness.jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.natural_cms_job")
                        && !sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq(JOB_ID)))
                .thenReturn(List.of(decided));

        NaturalCmsContract.JobResponse result = harness.store.decide(
                ACTOR,
                JOB_ID,
                new NaturalCmsContract.ApprovalDecisionRequest(
                        NaturalCmsContract.SCHEMA_VERSION,
                        PREVIEW_ID,
                        PREVIEW_HASH,
                        decision,
                        feedback));

        assertThat(result.jobId()).isEqualTo(JOB_ID);
        assertThat(result.stateVersion()).isEqualTo(2);
        assertThat(harness.updates).hasSize(2);
        UpdateCall stateUpdate = harness.updates.get(0);
        assertThat(normalize(stateUpdate.sql()))
                .contains("UPDATE app.natural_cms_job")
                .contains("state_version = state_version + 1");
        assertThat(stateUpdate.arguments())
                .containsExactly(
                        decision,
                        feedback,
                        ACTOR_ID,
                        decision,
                        Timestamp.from(NOW),
                        JOB_ID);
        UpdateCall outboxInsert = harness.updates.get(1);
        assertStrictNaturalCmsOutbox(outboxInsert, JOB_ID, 2, harness.objectMapper);
        harness.verifyCommittedCodingTransaction();
        verifyNoInteractions(harness.productJdbc, harness.productTransactionManager);
    }

    @ParameterizedTest
    @MethodSource("analyzeOutcomes")
    @SuppressWarnings("unchecked")
    void analyzeResultTransitionsTheJobAtTheAtomicRecordBoundary(
            String resultPort,
            String expectedStatus) {
        Harness harness = new Harness();
        NaturalCmsContract.StageExecutionResponse response =
                new NaturalCmsContract.StageExecutionResponse(
                        NaturalCmsContract.SCHEMA_VERSION,
                        RESULT_ID,
                        "cms.analyze",
                        resultPort,
                        RESOURCE,
                        null,
                        null,
                        null,
                        harness.objectMapper.createObjectNode().put("outcome", resultPort));
        NaturalCmsContract.HandlerResult stored = new NaturalCmsContract.HandlerResult(
                RESULT_ID,
                JOB_ID,
                TRACE_ID,
                1,
                "cms.analyze",
                resultPort,
                RESOURCE,
                null,
                null,
                null,
                response.payload(),
                NOW);
        when(harness.jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.coding_service_credential")),
                any(RowMapper.class),
                any(byte[].class),
                eq(Timestamp.from(NOW)),
                eq(Timestamp.from(NOW))))
                .thenReturn(List.of(CREDENTIAL_ID));
        when(harness.jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.natural_cms_job")
                        && sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq(JOB_ID)))
                .thenReturn(List.of(job(JOB_ID, 1, 1, "ACTIVE", null, null)));
        when(harness.jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.natural_cms_handler_result")),
                any(RowMapper.class),
                eq(JOB_ID),
                eq(1),
                eq(RESULT_ID)))
                .thenReturn(List.of(), List.of(stored));

        NaturalCmsContract.HandlerResult result = harness.store.record(
                AUTHORIZATION, JOB_ID, 1, response);

        assertThat(result.resultPort()).isEqualTo(resultPort);
        UpdateCall transition = harness.updateContaining("UPDATE app.natural_cms_job");
        assertThat(normalize(transition.sql()))
                .contains("SET status = ?, updated_at = ?")
                .contains("WHERE job_id = ? AND status <> 'COMPLETED'");
        assertThat(transition.arguments())
                .containsExactly(expectedStatus, Timestamp.from(NOW), JOB_ID);
        harness.verifyCommittedCodingTransaction();
        verifyNoInteractions(harness.productJdbc, harness.productTransactionManager);
    }

    private static Stream<Arguments> firstDecisions() {
        return Stream.of(
                Arguments.of("APPROVED", null, 1),
                Arguments.of("REJECTED", "Needs another revision", 2));
    }

    private static Stream<Arguments> analyzeOutcomes() {
        return Stream.of(
                Arguments.of("feasible", "ACTIVE"),
                Arguments.of("infeasible", "REJECTED"));
    }

    private static NaturalCmsContract.JobResponse job(
            UUID jobId,
            int pipelineAttempt,
            int stateVersion,
            String status,
            String decision,
            String feedback) {
        return new NaturalCmsContract.JobResponse(
                NaturalCmsContract.SCHEMA_VERSION,
                jobId,
                TRACE_ID,
                PROFILE_VERSION_ID,
                pipelineAttempt,
                stateVersion,
                status,
                "Update the article",
                RESOURCE,
                null,
                PREVIEW_ID,
                PREVIEW_HASH,
                "WAITING_APPROVAL".equals(status),
                decision,
                feedback,
                NOW.minusSeconds(60),
                NOW);
    }

    private static void assertStrictNaturalCmsOutbox(
            UpdateCall outbox,
            UUID jobId,
            int stateVersion,
            ObjectMapper objectMapper) throws Exception {
        assertThat(normalize(outbox.sql()))
                .contains("INSERT INTO app.transactional_outbox")
                .contains("VALUES (?, 'NATURAL_CMS_JOB', ?, 'NATURAL_CMS_JOB_REQUESTED'")
                .contains("ON CONFLICT (event_key) DO NOTHING");
        assertThat(outbox.arguments()).hasSize(8);
        assertThat(outbox.arguments()[1]).isEqualTo(jobId);
        assertThat(outbox.arguments()[2])
                .isEqualTo(jobId + ":natural-cms-requested:v" + stateVersion);
        assertThat(outbox.arguments()[3]).isEqualTo("axms:natural-cms:jobs:v1");
        JsonNode payload = objectMapper.readTree((String) outbox.arguments()[4]);
        assertThat(payload.size()).isEqualTo(2);
        assertThat(payload.path("schemaVersion").asText())
                .isEqualTo(NaturalCmsContract.SCHEMA_VERSION);
        assertThat(payload.path("jobId").asText()).isEqualTo(jobId.toString());
        assertThat(payload.has("requestText")).isFalse();
        assertThat(payload.has("resource")).isFalse();
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static final class Harness {

        private final AtomicBoolean inCodingTransaction = new AtomicBoolean();
        private final List<UpdateCall> updates = new ArrayList<>();
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            if ("update".equals(invocation.getMethod().getName())
                    && invocation.getMethod().isVarArgs()) {
                assertThat(inCodingTransaction)
                        .as("Natural CMS Job and outbox writes must share the coding transaction")
                        .isTrue();
                Object[] rawArguments = invocation.getRawArguments();
                updates.add(new UpdateCall(
                        (String) rawArguments[0], ((Object[]) rawArguments[1]).clone()));
                return 1;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        private final PlatformTransactionManager codingTransactionManager =
                mock(PlatformTransactionManager.class);
        private final TransactionStatus codingTransactionStatus = mock(TransactionStatus.class);
        private final TransactionTemplate transactions =
                new TransactionTemplate(codingTransactionManager);
        private final JdbcTemplate productJdbc = mock(JdbcTemplate.class);
        private final PlatformTransactionManager productTransactionManager =
                mock(PlatformTransactionManager.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final NaturalCmsStore store;

        private Harness() {
            when(codingTransactionManager.getTransaction(any())).thenAnswer(invocation -> {
                assertThat(inCodingTransaction.compareAndSet(false, true)).isTrue();
                return codingTransactionStatus;
            });
            org.mockito.Mockito.doAnswer(invocation -> {
                assertThat(inCodingTransaction.compareAndSet(true, false)).isTrue();
                return null;
            }).when(codingTransactionManager).commit(codingTransactionStatus);
            store = new NaturalCmsStore(
                    jdbc,
                    transactions,
                    productJdbc,
                    productTransactionManager,
                    objectMapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private void verifyCommittedCodingTransaction() {
            assertThat(inCodingTransaction).isFalse();
            verify(codingTransactionManager, times(1)).getTransaction(any());
            verify(codingTransactionManager, times(1)).commit(codingTransactionStatus);
            verify(codingTransactionManager, never()).rollback(codingTransactionStatus);
        }

        private UpdateCall updateContaining(String fragment) {
            List<UpdateCall> matches = updates.stream()
                    .filter(call -> call.sql().contains(fragment))
                    .toList();
            assertThat(matches).hasSize(1);
            return matches.get(0);
        }
    }

    private record UpdateCall(String sql, Object[] arguments) { }
}
