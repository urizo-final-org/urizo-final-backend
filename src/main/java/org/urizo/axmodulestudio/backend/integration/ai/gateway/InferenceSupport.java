package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Objects;
import java.util.Set;

/** The provider-native inference controls exposed for one registered model. */
public record InferenceSupport(
        InferenceSettings defaultSettings,
        Set<InferenceSettings.ReasoningIntensity> reasoningIntensities,
        BudgetRange reasoningBudgetTokens) {

    public InferenceSupport {
        defaultSettings = Objects.requireNonNull(defaultSettings, "defaultSettings is required");
        reasoningIntensities = Set.copyOf(Objects.requireNonNull(
                reasoningIntensities, "reasoningIntensities is required"));
        if (reasoningIntensities.isEmpty()
                || !reasoningIntensities.contains(defaultSettings.reasoningIntensity())) {
            throw new IllegalArgumentException("default reasoning intensity must be supported");
        }
        if (reasoningBudgetTokens == null && defaultSettings.reasoningBudgetTokens() != null) {
            throw new IllegalArgumentException("a budget default requires a budget range");
        }
        if (reasoningBudgetTokens != null && defaultSettings.reasoningBudgetTokens() != null
                && !reasoningBudgetTokens.supports(defaultSettings.reasoningBudgetTokens())) {
            throw new IllegalArgumentException("default reasoning budget is unsupported");
        }
    }

    public static InferenceSupport disabled() {
        return new InferenceSupport(InferenceSettings.none(),
                Set.of(InferenceSettings.ReasoningIntensity.NONE), null);
    }

    public boolean supports(InferenceSettings settings) {
        if (settings == null || !reasoningIntensities.contains(settings.reasoningIntensity())) {
            return false;
        }
        if (settings.reasoningIntensity() == InferenceSettings.ReasoningIntensity.NONE) {
            return settings.reasoningBudgetTokens() == null;
        }
        return reasoningBudgetTokens == null
                ? settings.reasoningBudgetTokens() == null
                : reasoningBudgetTokens.supports(settings.reasoningBudgetTokens());
    }

    public record BudgetRange(int min, int max, int multipleOf) {
        public BudgetRange {
            if (min < 1 || max < min || multipleOf < 1) {
                throw new IllegalArgumentException("inference budget range is invalid");
            }
        }

        boolean supports(Integer value) {
            return value != null && value >= min && value <= max
                    && value % multipleOf == 0;
        }
    }
}
