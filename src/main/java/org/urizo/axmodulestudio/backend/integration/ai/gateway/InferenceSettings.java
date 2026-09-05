package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Objects;

/** Immutable, provider-neutral inference controls carried with one model selection. */
public record InferenceSettings(ReasoningIntensity reasoningIntensity, Integer reasoningBudgetTokens) {

    public InferenceSettings {
        reasoningIntensity = Objects.requireNonNull(reasoningIntensity, "reasoningIntensity is required");
        if (reasoningBudgetTokens != null && reasoningBudgetTokens < 1) {
            throw new IllegalArgumentException("reasoningBudgetTokens must be positive");
        }
    }

    public static InferenceSettings none() {
        return new InferenceSettings(ReasoningIntensity.NONE, null);
    }

    public enum ReasoningIntensity { NONE, MINIMAL, LOW, MEDIUM, HIGH }
}
