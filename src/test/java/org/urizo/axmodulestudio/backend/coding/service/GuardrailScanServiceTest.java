package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailScanContract;

class GuardrailScanServiceTest {

    private static final UUID SCAN = UUID.fromString("12121212-1212-4121-8121-121212121212");
    private static final String BACKEND_BASE =
            "src/main/java/org/urizo/axmodulestudio/backend/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodingRunnerService runner = mock(CodingRunnerService.class);
    private final GuardrailScanService service = new GuardrailScanService(runner, mapper);

    private JsonNode runnerResult(String repository, String sha, String... folders) {
        ObjectNode result = mapper.createObjectNode();
        result.put("repo", repository);
        result.put("sha", sha);
        ArrayNode listed = result.putArray("folders");
        for (String folder : folders) {
            listed.add(folder);
        }
        return result;
    }

    @Test
    @DisplayName("A scan is queued as the runner command that reads dev, never a job worktree")
    void queuesTheScanCommand() {
        when(runner.enqueue(eq("PREPARE_SCAN_WORKTREE"), any())).thenReturn(SCAN);

        GuardrailScanContract.ScanAccepted accepted = service.request("backend");

        assertThat(accepted.scanId()).isEqualTo(SCAN);
        assertThat(accepted.repository()).isEqualTo("backend");
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(runner).enqueue(eq("PREPARE_SCAN_WORKTREE"), payload.capture());
        assertThat(payload.getValue().path("repo").asText()).isEqualTo("backend");
    }

    @Test
    @DisplayName("An unregistered repository is refused before anything is queued")
    void refusesAnUnknownRepository() {
        assertThatThrownBy(() -> service.request("master"))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("not registered");
        verify(runner, never()).enqueue(any(), any());
    }

    @Test
    @DisplayName("The fixed Denylist is removed from what the runner reported")
    void hidesDeniedFolders() {
        when(runner.taskOutcome(SCAN, "PREPARE_SCAN_WORKTREE")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null, runnerResult(
                        "backend", "abc123",
                        BACKEND_BASE + "auth", BACKEND_BASE + "cms", BACKEND_BASE + "coding",
                        BACKEND_BASE + "core", BACKEND_BASE + "health",
                        BACKEND_BASE + "integration", BACKEND_BASE + "knowledge",
                        BACKEND_BASE + "orchestration")));

        GuardrailScanContract.ScanResult result = service.result(SCAN, "backend");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.sha()).isEqualTo("abc123");
        assertThat(result.folders()).containsExactly(
                BACKEND_BASE + "cms", BACKEND_BASE + "core",
                BACKEND_BASE + "health", BACKEND_BASE + "integration");
    }

    @Test
    @DisplayName("A scan still waiting for a runner reports its state rather than failing")
    void reportsAPendingScan() {
        when(runner.taskOutcome(SCAN, "PREPARE_SCAN_WORKTREE")).thenReturn(
                new CodingRunnerService.TaskOutcome("PENDING", null, null));

        GuardrailScanContract.ScanResult result = service.result(SCAN, "backend");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.sha()).isNull();
        assertThat(result.folders()).isEmpty();
    }

    @Test
    @DisplayName("A failed scan carries the runner error code through")
    void reportsAFailedScan() {
        when(runner.taskOutcome(SCAN, "PREPARE_SCAN_WORKTREE")).thenReturn(
                new CodingRunnerService.TaskOutcome("FAILED", "RUNNER_SCAN_FAILED", null));

        GuardrailScanContract.ScanResult result = service.result(SCAN, "backend");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("RUNNER_SCAN_FAILED");
    }

    @Test
    @DisplayName("A scan of one repository cannot be read as another")
    void refusesAMismatchedRepository() {
        when(runner.taskOutcome(SCAN, "PREPARE_SCAN_WORKTREE")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null, runnerResult(
                        "frontend", "abc123", "src/features/cms")));

        assertThatThrownBy(() -> service.result(SCAN, "backend"))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("The Frontend scan keeps the folders the demo actually edits")
    void keepsFrontendProductFolders() {
        when(runner.taskOutcome(SCAN, "PREPARE_SCAN_WORKTREE")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null, runnerResult(
                        "frontend", "def456",
                        "src/features/auth", "src/features/cms", "src/features/coding",
                        "src/features/knowledge", "src/features/ops",
                        "src/features/orchestration", "src/features/site",
                        "src/app", "src/shared/api", "src/shared/ui", "src/styles")));

        GuardrailScanContract.ScanResult result = service.result(SCAN, "frontend");

        assertThat(result.folders()).containsExactly(
                "src/features/cms", "src/features/ops", "src/features/site",
                "src/app", "src/shared/api", "src/shared/ui", "src/styles");
    }

    @Test
    @DisplayName("A reused scan folder with no reported folders yields an empty list, not an error")
    void toleratesAResultWithoutFolders() {
        ObjectNode result = mapper.createObjectNode();
        result.put("repo", "backend");
        result.put("sha", "unchanged");
        when(runner.taskOutcome(SCAN, "PREPARE_SCAN_WORKTREE")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null, result));

        assertThat(service.result(SCAN, "backend").folders()).isEmpty();
    }
}
