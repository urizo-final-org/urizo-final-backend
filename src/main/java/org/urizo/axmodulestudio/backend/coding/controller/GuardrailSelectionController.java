package org.urizo.axmodulestudio.backend.coding.controller;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailSelectionContract;
import org.urizo.axmodulestudio.backend.coding.service.GuardrailPathSelectionService;

/**
 * The stored guardrail choice. The folders it refers to come from the scan, never from here.
 *
 * <p>Registered under {@code /api/admin/coding/guardrail/**}, which is SUPER_ADMIN only.
 */
@RestController
@Validated
@RequestMapping("/api/admin/coding/guardrail/selections")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class GuardrailSelectionController {

    private final GuardrailPathSelectionService selections;

    GuardrailSelectionController(GuardrailPathSelectionService selections) {
        this.selections = selections;
    }

    @GetMapping
    public GuardrailSelectionContract.SelectionList list(@RequestParam String repository) {
        return selections.selections(repository);
    }

    @PutMapping
    public GuardrailSelectionContract.SelectionList save(
            @Valid @RequestBody GuardrailSelectionContract.SaveRequest request) {
        return selections.save(request);
    }
}
