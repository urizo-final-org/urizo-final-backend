package org.urizo.axmodulestudio.backend.coding.integration;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.coding.service.CodingRunnerService;

/** Delegates the single allowlisted local Compose deployment to the privileged host runner. */
@Component
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class LocalDockerComposeDeploymentAdapter implements DeploymentAdapter {

    public static final String ADAPTER_KEY = "local-docker-compose";
    public static final String TARGET_KEY = "full:backend:spring-app";
    public static final String CONFIG_DIGEST =
            "sha256:893cadbefbccb19b6354583b077dc7187c3185441b0b416a08148274b314c349";

    private final CodingRunnerService runner;

    public LocalDockerComposeDeploymentAdapter(CodingRunnerService runner) {
        this.runner = Objects.requireNonNull(runner, "runner is required");
    }

    @Override
    public String adapterKey() { return ADAPTER_KEY; }

    @Override
    public String targetKey() { return TARGET_KEY; }

    @Override
    public String configDigest() { return CONFIG_DIGEST; }

    @Override
    public DeploymentOutcome deploy(UUID executionId, JsonNode payload) {
        runner.enqueue(executionId, "DEPLOY_LOCAL_COMPOSE", payload);
        CodingRunnerService.TaskOutcome outcome = runner.taskOutcome(
                executionId, "DEPLOY_LOCAL_COMPOSE");
        return switch (outcome.status()) {
            case "PENDING", "RUNNING" -> new DeploymentOutcome(
                    Status.PENDING, outcome.result(), null);
            case "SUCCEEDED" -> validReceipt(payload, outcome.result())
                    ? new DeploymentOutcome(Status.COMPLETED, outcome.result(), null)
                    : new DeploymentOutcome(
                            Status.BLOCKED, outcome.result(), "RUNNER_DEPLOY_RECEIPT_INVALID");
            default -> new DeploymentOutcome(
                    Status.BLOCKED, outcome.result(), outcome.errorCode());
        };
    }

    private static boolean validReceipt(JsonNode request, JsonNode receipt) {
        return receipt != null
                && ADAPTER_KEY.equals(receipt.path("adapter").asText())
                && TARGET_KEY.equals(receipt.path("target").asText())
                && "COMPLETED".equals(receipt.path("status").asText())
                && request.path("mergeSha").asText().equals(
                        receipt.path("sourceSha").asText());
    }
}
