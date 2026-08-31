package org.urizo.axmodulestudio.backend.coding.repository;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnAccessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleService;

@SpringBootTest(properties = {
        "ax.coding.model-turn-bridge.enabled=true",
        "ax.coding.job-lifecycle.enabled=true"
})
@ActiveProfiles({"dev", "coding-job-local-fixture"})
@EnabledIfEnvironmentVariable(named = "AXMS_RUN_CODING_DB_INTEGRATION", matches = "true")
class JdbcCodingModelTurnGuardIntegrationTest {

    private static final UUID TRACE_ID = UUID.fromString("86666666-6666-4666-8666-666666666666");
    private static final String CONTEXT_DIGEST =
            "sha256:8bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private CodingModelTurnGuard guard;

    @Autowired
    private CodingJobLifecycleService jobLifecycleService;

    @Test
    void authenticatesAuthorizesReservesCompletesAndReplaysAgainstCoreDb() throws Exception {
        CodingJobLifecycleContract.JobResponse job = seedJob();
        String suffix = UUID.randomUUID().toString();
        byte[] tokenBytes = Files.readAllBytes(Path.of(
                ".local", "secrets", "coding_model_bridge_service_token"));
        String authorization = "Bearer " + new String(tokenBytes, StandardCharsets.US_ASCII);
        Arrays.fill(tokenBytes, (byte) 0);
        try {
            CodingModelTurnContract.Request request = request(
                    job.jobId(),
                    "stage4.db.guard." + suffix,
                    UUID.fromString("8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                    job.stateVersion());
            CodingModelTurnPermit permit = guard.reserve(authorization, request);
            assertThat(permit.replay()).isFalse();

            CodingModelTurnContract.Response response = response(request);
            guard.complete(permit, response);

            CodingModelTurnPermit replay = guard.reserve(authorization, request);
            assertThat(replay.replay()).isTrue();
            assertThat(replay.cachedResponse()).isEqualTo(response);

            CodingModelTurnContract.Request reused = request(
                    job.jobId(),
                    request.idempotencyKey(),
                    UUID.fromString("8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"),
                    job.stateVersion());
            assertThatThrownBy(() -> guard.reserve(authorization, reused))
                    .isInstanceOfSatisfying(CodingModelTurnAccessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

            CodingModelTurnContract.Request stale = request(
                    job.jobId(),
                    "stage4.db.guard.stale." + suffix,
                    UUID.fromString("8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaac"),
                    job.stateVersion() + 1);
            assertThatThrownBy(() -> guard.reserve(authorization, stale))
                    .isInstanceOfSatisfying(CodingModelTurnAccessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("JOB_STATE_VERSION_CONFLICT"));

            CodingModelTurnContract.Request active = request(
                    job.jobId(),
                    "stage4.db.guard.active." + suffix,
                    UUID.fromString("8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaad"),
                    job.stateVersion());
            CodingModelTurnPermit activePermit = guard.reserve(authorization, active);
            assertThatThrownBy(() -> guard.reserve(authorization, active))
                    .isInstanceOfSatisfying(CodingModelTurnAccessException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                        assertThat(failure.retryable()).isTrue();
                    });
            // A diagnostic must not reach response_json: ck_coding_model_turn_completion
            // ties that column to COMPLETED, and an update that breaks it leaves the turn
            // stranded IN_PROGRESS with the failure unrecorded.
            guard.fail(activePermit, "MODEL_CAPABILITY_UNSUPPORTED", false,
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                            .put("reason", "TOP_LEVEL_SHAPE"));
            assertThatThrownBy(() -> guard.reserve(authorization, active))
                    .isInstanceOfSatisfying(CodingModelTurnAccessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MODEL_CAPABILITY_UNSUPPORTED"));

            assertThatThrownBy(() -> guard.reserve("Bearer invalid-local-test-token", request))
                    .isInstanceOfSatisfying(CodingModelTurnAccessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SERVICE_AUTHENTICATION_FAILED"));
        }
        finally {
            deleteJob(job.jobId());
        }
    }

    private static CodingModelTurnContract.Request request(
            UUID jobId,
            String idempotencyKey,
            UUID turnId,
            int expectedStateVersion) {
        return new CodingModelTurnContract.Request(
                "1.0",
                turnId,
                jobId,
                TRACE_ID,
                idempotencyKey,
                1,
                expectedStateVersion,
                "plan",
                "coding-plan-v1",
                CONTEXT_DIGEST,
                List.of("CHAT"),
                List.of(JsonNodeFactory.instance.objectNode()
                        .put("role", "user")
                        .put("content", "Local DB guard integration fixture.")),
                List.of(),
                JsonNodeFactory.instance.objectNode().put("type", "TEXT"),
                Instant.now().plusSeconds(60));
    }

    private static CodingModelTurnContract.Response response(CodingModelTurnContract.Request request) {
        return new CodingModelTurnContract.Response(
                "1.0",
                request.turnId(),
                request.jobId(),
                request.traceId(),
                request.idempotencyKey(),
                new CodingModelTurnContract.Assistant("assistant", "Local cached response fixture."),
                List.of(),
                CodingModelTurnContract.TextResponseFormat.text(),
                new CodingModelTurnContract.SelectedModel("OPENAI", "local-test-model"),
                new CodingModelTurnContract.TokenUsage(2, 2, 4),
                10,
                "STOP",
                Instant.now());
    }

    private CodingJobLifecycleContract.JobResponse seedJob() {
        String suffix = UUID.randomUUID().toString();
        CodingJobLifecycleContract.JobResponse created = jobLifecycleService.create(
                TRACE_ID,
                "stage4.db.job.create." + suffix,
                new CodingJobLifecycleContract.CreateRequest(
                        "1.0",
                        UUID.fromString("77777777-7777-4777-8777-777777777777"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "plan",
                        "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        CONTEXT_DIGEST,
                        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        "coding-plan-v1",
                        List.of("CHAT"),
                        List.of("plan"),
                        Instant.now().plusSeconds(600)));
        return jobLifecycleService.transition(
                created.jobId(),
                TRACE_ID,
                "stage4.db.job.start." + suffix,
                new CodingJobLifecycleContract.TransitionRequest(
                        "1.0",
                        created.stateVersion(),
                        CodingJobLifecycleContract.Status.RUNNING,
                        null));
    }

    private static void deleteJob(UUID jobId) throws Exception {
        try (Connection connection = devOperatorConnection()) {
            deleteJob(connection, jobId);
        }
    }

    private static void deleteJob(Connection connection, UUID jobId) throws Exception {
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
