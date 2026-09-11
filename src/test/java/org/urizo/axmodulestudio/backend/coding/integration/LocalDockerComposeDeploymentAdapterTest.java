package org.urizo.axmodulestudio.backend.coding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.service.CodingRunnerService;

class LocalDockerComposeDeploymentAdapterTest {

    @Test
    void advertisesOnlyTheRepositoriesWithAnAllowlistedDeploymentTarget() {
        var adapter = new LocalDockerComposeDeploymentAdapter(mock(CodingRunnerService.class));
        assertThat(adapter.supportsRepository("backend")).isTrue();
        assertThat(adapter.supportsRepository("frontend")).isTrue();
        assertThat(adapter.supportsRepository("unknown")).isFalse();
        assertThat(adapter.supportsRepository(null)).isFalse();
        assertThat(adapter.targetKey("backend")).isEqualTo("full:backend:spring-app");
        assertThat(adapter.targetKey("frontend")).isEqualTo("full:frontend:frontend");
        assertThat(adapter.targetKey("unknown")).isNull();
        assertThat(adapter.targetKey(null)).isNull();
    }

    @Test
    void delegatesOnlyTheFixedLocalComposeTaskUnderTheStableExecutionId() {
        CodingRunnerService runner = mock(CodingRunnerService.class);
        UUID executionId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var payload = JsonNodeFactory.instance.objectNode()
                .put("repository", "backend")
                .put("mergeSha", "sha1:1111111111111111111111111111111111111111");
        when(runner.taskOutcome(executionId, "DEPLOY_LOCAL_COMPOSE"))
                .thenReturn(new CodingRunnerService.TaskOutcome(
                        "SUCCEEDED", null,
                        JsonNodeFactory.instance.objectNode()
                                .put("adapter", "local-docker-compose")
                                .put("target", "full:backend:spring-app")
                                .put("sourceSha", payload.path("mergeSha").asText())
                                .put("status", "COMPLETED")));
        LocalDockerComposeDeploymentAdapter adapter =
                new LocalDockerComposeDeploymentAdapter(runner);

        DeploymentAdapter.DeploymentOutcome outcome = adapter.deploy(executionId, payload);

        verify(runner).enqueue(executionId, "DEPLOY_LOCAL_COMPOSE", payload);
        assertThat(outcome.status()).isEqualTo(DeploymentAdapter.Status.COMPLETED);
        assertThat(adapter.configDigest()).matches("^sha256:[0-9a-f]{64}$");
    }

    /* The frontend target is its own service. A receipt that deployed the backend service for
     * a frontend request must not count, even though the merge sha matches. */
    @Test
    void acceptsAFrontendReceiptOnlyForTheFrontendTarget() {
        CodingRunnerService runner = mock(CodingRunnerService.class);
        UUID executionId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        var payload = JsonNodeFactory.instance.objectNode()
                .put("repository", "frontend")
                .put("mergeSha", "sha1:1111111111111111111111111111111111111111");
        var receipt = JsonNodeFactory.instance.objectNode()
                .put("adapter", "local-docker-compose")
                .put("target", "full:frontend:frontend")
                .put("sourceSha", payload.path("mergeSha").asText())
                .put("status", "COMPLETED");
        when(runner.taskOutcome(executionId, "DEPLOY_LOCAL_COMPOSE"))
                .thenReturn(new CodingRunnerService.TaskOutcome("SUCCEEDED", null, receipt));
        LocalDockerComposeDeploymentAdapter adapter =
                new LocalDockerComposeDeploymentAdapter(runner);

        assertThat(adapter.deploy(executionId, payload).status())
                .isEqualTo(DeploymentAdapter.Status.COMPLETED);

        receipt.put("target", "full:backend:spring-app");
        DeploymentAdapter.DeploymentOutcome wrongService = adapter.deploy(executionId, payload);
        assertThat(wrongService.status()).isEqualTo(DeploymentAdapter.Status.BLOCKED);
        assertThat(wrongService.errorCode()).isEqualTo("RUNNER_DEPLOY_RECEIPT_INVALID");
    }

    @Test
    void blocksASuccessReceiptThatDidNotDeployTheApprovedMergeSha() {
        CodingRunnerService runner = mock(CodingRunnerService.class);
        UUID executionId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        var payload = JsonNodeFactory.instance.objectNode()
                .put("repository", "backend")
                .put("mergeSha", "sha1:1111111111111111111111111111111111111111");
        when(runner.taskOutcome(executionId, "DEPLOY_LOCAL_COMPOSE"))
                .thenReturn(new CodingRunnerService.TaskOutcome(
                        "SUCCEEDED", null,
                        JsonNodeFactory.instance.objectNode()
                                .put("adapter", "local-docker-compose")
                                .put("target", "full:backend:spring-app")
                                .put("sourceSha", "sha1:2222222222222222222222222222222222222222")
                                .put("status", "COMPLETED")));

        DeploymentAdapter.DeploymentOutcome outcome =
                new LocalDockerComposeDeploymentAdapter(runner).deploy(executionId, payload);

        assertThat(outcome.status()).isEqualTo(DeploymentAdapter.Status.BLOCKED);
        assertThat(outcome.errorCode()).isEqualTo("RUNNER_DEPLOY_RECEIPT_INVALID");
    }
}
