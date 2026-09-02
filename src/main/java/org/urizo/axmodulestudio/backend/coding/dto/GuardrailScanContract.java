package org.urizo.axmodulestudio.backend.coding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The guardrail folder scan an administrator runs before choosing what the model may change.
 *
 * <p>The folder list is never stored. It is read from the repository each time so a folder added
 * later appears on its own, and a stored selection can only ever name a folder this scan offered.
 */
public final class GuardrailScanContract {

    /** The repositories the runner can resolve to a path. Anything else is refused. */
    public static final Set<String> REPOSITORIES = Set.of("backend", "frontend");

    private GuardrailScanContract() { }

    public record ScanRequest(
            @NotBlank @Pattern(regexp = "^(backend|frontend)$") String repository) { }

    public record ScanAccepted(UUID scanId, String repository) { }

    /**
     * @param status   the queued command's state: a scan is still waiting while no runner runs
     * @param sha      the {@code origin/dev} commit the folders were read from, once known
     * @param folders  repository-relative folder paths, with the fixed Denylist already removed
     */
    public record ScanResult(
            UUID scanId,
            String repository,
            String status,
            String sha,
            List<String> folders,
            String errorCode) { }
}
