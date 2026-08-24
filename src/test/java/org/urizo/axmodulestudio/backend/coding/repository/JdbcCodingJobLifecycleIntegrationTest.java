package org.urizo.axmodulestudio.backend.coding.repository;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "ax.coding.job-lifecycle.enabled=true")
@ActiveProfiles({"dev", "coding-job-local-fixture"})
@EnabledIfEnvironmentVariable(named = "AXMS_RUN_CODING_DB_INTEGRATION", matches = "true")
class JdbcCodingJobLifecycleIntegrationTest {

    @Autowired
    private CodingJobLifecycleService service;

    @Test
    void createsReplaysAndSerializesTheAuthoritativeStateMachine() throws Exception {
        UUID traceId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        CodingJobLifecycleContract.CreateRequest createRequest = createRequest();
        CodingJobLifecycleContract.JobResponse created = service.create(
                traceId, "job.create." + suffix, createRequest);
        UUID jobId = created.jobId();
        try {
            CodingJobLifecycleContract.JobResponse createReplay = service.create(
                    traceId, "job.create." + suffix, createRequest);
            assertThat(createReplay).isEqualTo(created);
            assertThat(created.status()).isEqualTo(CodingJobLifecycleContract.Status.PENDING);
            assertThat(created.stateVersion()).isEqualTo(1);

            CodingJobLifecycleContract.CreateRequest changed = new CodingJobLifecycleContract.CreateRequest(
                    createRequest.schemaVersion(),
                    createRequest.actorId(),
                    createRequest.projectId(),
                    createRequest.repositoryId(),
                    createRequest.graphStep(),
                    createRequest.baseSha(),
                    createRequest.contextDigest(),
                    "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                    createRequest.promptVersion(),
                    createRequest.allowedCapabilities(),
                    createRequest.allowedNodes(),
                    createRequest.expiresAt());
            assertThatThrownBy(() -> service.create(traceId, "job.create." + suffix, changed))
                    .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                            failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

            CodingJobLifecycleContract.TransitionRequest start = transition(
                    1, CodingJobLifecycleContract.Status.RUNNING);
            CodingJobLifecycleContract.JobResponse running = service.transition(
                    jobId, traceId, "job.start." + suffix, start);
            assertThat(running.status()).isEqualTo(CodingJobLifecycleContract.Status.RUNNING);
            assertThat(running.stateVersion()).isEqualTo(2);
            assertThat(running.startedAt()).isNotNull();
            assertThat(service.transition(
                    jobId, traceId, "job.start." + suffix, start)).isEqualTo(running);

            assertThatThrownBy(() -> service.transition(
                    jobId, traceId, "job.stale." + suffix,
                    transition(1, CodingJobLifecycleContract.Status.COMPLETED)))
                    .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("JOB_STATE_VERSION_CONFLICT"));

            CodingJobLifecycleContract.JobResponse waiting = service.transition(
                    jobId, traceId, "job.wait." + suffix,
                    transition(2, CodingJobLifecycleContract.Status.WAITING_APPROVAL));
            assertThat(waiting.stateVersion()).isEqualTo(3);

            assertThatThrownBy(() -> service.transition(
                    jobId, traceId, "job.invalid." + suffix,
                    transition(3, CodingJobLifecycleContract.Status.COMPLETED)))
                    .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("JOB_STATE_TRANSITION_DENIED"));

            CodingJobLifecycleContract.JobResponse resumed = service.transition(
                    jobId, traceId, "job.resume." + suffix,
                    transition(3, CodingJobLifecycleContract.Status.RUNNING));
            CodingJobLifecycleContract.JobResponse completed = service.transition(
                    jobId, traceId, "job.complete." + suffix,
                    transition(4, CodingJobLifecycleContract.Status.COMPLETED));
            assertThat(resumed.startedAt()).isEqualTo(running.startedAt());
            assertThat(completed.stateVersion()).isEqualTo(5);
            assertThat(completed.finishedAt()).isNotNull();
            assertThat(service.find(jobId, traceId)).isEqualTo(completed);

            assertThatThrownBy(() -> service.transition(
                    jobId, traceId, "job.terminal." + suffix,
                    transition(5, CodingJobLifecycleContract.Status.RUNNING)))
                    .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                            failure -> assertThat(failure.code()).isEqualTo("JOB_TERMINAL"));
        }
        finally {
            deleteJob(jobId);
        }
    }

    private static CodingJobLifecycleContract.CreateRequest createRequest() {
        return new CodingJobLifecycleContract.CreateRequest(
                "1.0",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "plan",
                "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "coding-plan-v1",
                List.of("CHAT"),
                List.of("plan"),
                Instant.now().plusSeconds(600));
    }

    private static CodingJobLifecycleContract.TransitionRequest transition(
            int expectedStateVersion,
            CodingJobLifecycleContract.Status target) {
        return new CodingJobLifecycleContract.TransitionRequest(
                "1.0", expectedStateVersion, target, null);
    }

    private static void deleteJob(UUID jobId) throws Exception {
        try (Connection connection = devOperatorConnection()) {
            try (PreparedStatement turns = connection.prepareStatement(
                    "DELETE FROM app.coding_model_turn_idempotency WHERE job_id = ?")) {
                turns.setObject(1, jobId);
                turns.executeUpdate();
            }
            try (PreparedStatement commands = connection.prepareStatement(
                    "DELETE FROM app.coding_job_lifecycle_command WHERE job_id = ?")) {
                commands.setObject(1, jobId);
                commands.executeUpdate();
            }
            try (PreparedStatement jobs = connection.prepareStatement(
                    "DELETE FROM app.coding_job WHERE job_id = ?")) {
                jobs.setObject(1, jobId);
                jobs.executeUpdate();
            }
        }
    }

    private static Connection devOperatorConnection() throws Exception {
        String password = Files.readString(
                Path.of(".local", "secrets", "dev_operator_password"),
                StandardCharsets.UTF_8).trim();
        try {
            return DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:15432/ax_module_studio",
                    "dev_operator",
                    password);
        }
        finally {
            password = null;
        }
    }
}
