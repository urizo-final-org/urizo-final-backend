package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

@SpringBootTest(properties = "ax.coding.job-lifecycle.enabled=true")
@ActiveProfiles({"dev", "coding-job-local-fixture"})
@EnabledIfEnvironmentVariable(named = "AXMS_RUN_CODING_DB_INTEGRATION", matches = "true")
class CodingHandlerCommandIntegrationTest {

    @Autowired
    private CodingHandlerCommandService service;

    @Autowired
    @Qualifier("codingJobLifecycleJdbcTemplate")
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("codingJobLifecycleTransactionTemplate")
    private TransactionTemplate transactions;

    @Test
    void createsJobOutboxRequestAndAttemptInOneRollbackSafeTransaction() {
        UUID actorId = UUID.randomUUID();
        AuthenticatedActor actor =
                new AuthenticatedActor(actorId, "integration-admin", AdminRole.GENERAL_ADMIN);
        String suffix = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> {
            CodingHandlerContract.CreateCodingJobResponse created = service.create(
                    actor,
                    UUID.randomUUID(),
                    "coding.wrapper." + suffix,
                    new CodingHandlerContract.CreateCodingJobRequest(
                            "1.0",
                            UUID.fromString("77777777-7777-4777-8777-777777777777"),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "start",
                            "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                            "coding-handler-v2",
                            List.of("TOOL_CALLING"),
                            List.of("start"),
                            Instant.now().plusSeconds(600),
                            "Create the approved local coding candidate."));
            assertThat(created.job().actorId()).isEqualTo(actorId);
            assertThat(committedState(created.job().jobId()))
                    .isEqualTo(new CommittedState(true, true, true));
            status.setRollbackOnly();
        });
    }

    private CommittedState committedState(UUID jobId) {
        return jdbc.queryForObject("""
                        SELECT EXISTS (
                                   SELECT 1 FROM app.coding_job_request WHERE job_id = ?),
                               EXISTS (
                                   SELECT 1 FROM app.coding_pipeline_attempt
                                   WHERE job_id = ? AND pipeline_attempt = 1 AND status = 'ACTIVE'),
                               EXISTS (
                                   SELECT 1 FROM app.transactional_outbox
                                   WHERE aggregate_type = 'CODING_JOB' AND aggregate_id = ?)
                        """, (result, row) -> new CommittedState(
                        result.getBoolean(1), result.getBoolean(2), result.getBoolean(3)),
                jobId, jobId, jobId);
    }

    private record CommittedState(boolean request, boolean attempt, boolean outbox) { }
}
