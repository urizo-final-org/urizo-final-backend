package org.urizo.axmodulestudio.backend.coding.integration;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.coding.service.CodingRunnerService;

/**
 * Delegates the allowlisted local Compose deployments to the privileged host runner.
 *
 * One fixed target per repository, chosen here and never by the graph: the backend deploys
 * as the running Spring service, the frontend as the running Frontend service. Anything else
 * has no target and therefore no deployment.
 */
@Component
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class LocalDockerComposeDeploymentAdapter implements DeploymentAdapter {

    public static final String ADAPTER_KEY = "local-docker-compose";
    public static final String BACKEND_TARGET_KEY = "full:backend:spring-app";
    public static final String FRONTEND_TARGET_KEY = "full:frontend:frontend";
    /**
     * Identity of this allowlist (sha256 over adapter key and target keys, one per line).
     * It changes whenever a target is added, so a deployment request recorded against the
     * previous allowlist is refused at the deploy stage instead of silently re-routed.
     */
    public static final String CONFIG_DIGEST =
            "sha256:ad46325649cd626a72daf5e9cb1eb59cdb8997f6ebb23c472af650a2a1a3a00a";

    private static final Map<String, String> TARGETS = Map.of(
            "backend", BACKEND_TARGET_KEY,
            "frontend", FRONTEND_TARGET_KEY);

    private final CodingRunnerService runner;

    public LocalDockerComposeDeploymentAdapter(CodingRunnerService runner) {
        this.runner = Objects.requireNonNull(runner, "runner is required");
    }

    @Override
    public boolean supportsRepository(String repository) {
        return repository != null && TARGETS.containsKey(repository);
    }

    @Override
    public String adapterKey() { return ADAPTER_KEY; }

    @Override
    public String targetKey(String repository) {
        return repository == null ? null : TARGETS.get(repository);
    }

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

    /* The receipt must name the target this adapter chose for the request's repository:
     * a backend service deployed for a frontend request is not a frontend deployment. */
    private static boolean validReceipt(JsonNode request, JsonNode receipt) {
        String expectedTarget = TARGETS.get(request.path("repository").asText());
        return receipt != null
                && expectedTarget != null
                && ADAPTER_KEY.equals(receipt.path("adapter").asText())
                && expectedTarget.equals(receipt.path("target").asText())
                && "COMPLETED".equals(receipt.path("status").asText())
                && request.path("mergeSha").asText().equals(
                        receipt.path("sourceSha").asText());
    }
}
