package org.urizo.axmodulestudio.backend.coding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailScanContract;

/**
 * Offers the administrator the folders the model may be allowed into.
 *
 * <p>The Backend runs in a container and cannot read the host checkout, so the runner reads the
 * folders from its job-independent {@code origin/dev} scan worktree and reports them back. The
 * runner returns them raw: the fixed Denylist is applied here so only one copy of that list exists.
 */
@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class GuardrailScanService {

    private static final String SCAN_KIND = "PREPARE_SCAN_WORKTREE";

    private final CodingRunnerService runner;
    private final ObjectMapper objectMapper;

    GuardrailScanService(CodingRunnerService runner, ObjectMapper objectMapper) {
        this.runner = Objects.requireNonNull(runner, "runner is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public GuardrailScanContract.ScanAccepted request(String repository) {
        requireKnownRepository(repository);
        UUID scanId = runner.enqueue(
                SCAN_KIND, objectMapper.createObjectNode().put("repo", repository));
        return new GuardrailScanContract.ScanAccepted(scanId, repository);
    }

    public GuardrailScanContract.ScanResult result(UUID scanId, String repository) {
        requireKnownRepository(repository);
        CodingRunnerService.TaskOutcome outcome = runner.taskOutcome(scanId, SCAN_KIND);
        JsonNode result = outcome.result();
        if (result == null) {
            // Still queued or running. With no runner up a scan simply waits, which is a state to
            // show rather than an error to raise.
            return new GuardrailScanContract.ScanResult(
                    scanId, repository, outcome.status(), null, List.of(), outcome.errorCode());
        }
        String scanned = result.path("repo").asText();
        if (!repository.equals(scanned)) {
            throw new CodingWorkerException(
                    "RUNNER_TASK_NOT_FOUND",
                    "The runner command was not found.",
                    HttpStatus.NOT_FOUND);
        }
        String sha = result.path("sha").isTextual() ? result.path("sha").textValue() : null;
        return new GuardrailScanContract.ScanResult(
                scanId, repository, outcome.status(), sha,
                GuardrailPathPolicy.visibleFolders(folders(result)), outcome.errorCode());
    }

    private static List<String> folders(JsonNode result) {
        List<String> folders = new ArrayList<>();
        for (JsonNode folder : result.path("folders")) {
            if (folder.isTextual()) {
                folders.add(folder.textValue());
            }
        }
        return folders;
    }

    private static void requireKnownRepository(String repository) {
        if (!GuardrailScanContract.REPOSITORIES.contains(repository)) {
            throw new CodingWorkerException(
                    "GUARDRAIL_REPOSITORY_NOT_REGISTERED",
                    "The repository is not registered for a guardrail scan.",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
