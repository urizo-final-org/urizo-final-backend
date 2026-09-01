package org.urizo.axmodulestudio.backend.coding.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Which scanned folders the administrator allows the Coding model into.
 *
 * <p>Only the choice is stored; the folders themselves come from a fresh scan every time. A path
 * with no stored row is off, so a folder created later never starts out reachable.
 */
public final class GuardrailSelectionContract {

    private GuardrailSelectionContract() { }

    public record Selection(
            @NotBlank @Size(max = 512) String path,
            boolean enabled,
            @Size(max = 120) String label) { }

    /** Replaces the whole stored choice for one repository. */
    public record SaveRequest(
            @NotBlank @Pattern(regexp = "^(backend|frontend)$") String repository,
            @NotNull @Size(max = 200) List<@Valid Selection> selections) { }

    public record SelectionList(String repository, List<Selection> selections) { }
}
