package org.urizo.axmodulestudio.backend.coding.controller;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailRuleContract;
import org.urizo.axmodulestudio.backend.coding.service.GuardrailRuleService;

/**
 * The guardrail rules that do not name a path.
 *
 * <p>Registered under {@code /api/admin/coding/guardrail/**}, which is SUPER_ADMIN only.
 */
@RestController
@Validated
@RequestMapping("/api/admin/coding/guardrail/rules")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class GuardrailRuleController {

    private final GuardrailRuleService rules;

    GuardrailRuleController(GuardrailRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public GuardrailRuleContract.Rules read() {
        return rules.rules();
    }

    @PutMapping
    public GuardrailRuleContract.Rules save(
            @Valid @RequestBody GuardrailRuleContract.Rules request) {
        return rules.save(request);
    }
}
