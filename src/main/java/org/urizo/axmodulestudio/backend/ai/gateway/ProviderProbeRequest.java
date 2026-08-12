package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Instant;
import java.util.Objects;

public record ProviderProbeRequest(
        ModelProvider provider,
        String modelId,
        String caseId,
        ModelUseCase useCase,
        String fixedPrompt,
        int repetition,
        Instant deadline) {

    public ProviderProbeRequest {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        caseId = Objects.requireNonNull(caseId, "caseId is required");
        useCase = Objects.requireNonNull(useCase, "useCase is required");
        fixedPrompt = Objects.requireNonNull(fixedPrompt, "fixedPrompt is required");
        deadline = Objects.requireNonNull(deadline, "deadline is required");

        if (modelId.isBlank() || caseId.isBlank() || fixedPrompt.isBlank()) {
            throw new IllegalArgumentException("probe identifiers and fixedPrompt cannot be blank");
        }
        if (repetition < 1 || repetition > 100) {
            throw new IllegalArgumentException("repetition must be between 1 and 100");
        }
    }
}
