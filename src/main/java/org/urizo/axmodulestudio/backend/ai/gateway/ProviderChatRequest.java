package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Instant;
import java.util.Objects;

public record ProviderChatRequest(
        ModelProvider provider,
        String modelId,
        String prompt,
        Instant deadline) {

    private static final int MAX_PROMPT_CHARACTERS = 65_536;

    public ProviderChatRequest {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        prompt = Objects.requireNonNull(prompt, "prompt is required");
        deadline = Objects.requireNonNull(deadline, "deadline is required");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be blank");
        }
        if (prompt.isBlank() || prompt.length() > MAX_PROMPT_CHARACTERS) {
            throw new IllegalArgumentException("prompt must contain between 1 and 65536 characters");
        }
    }

    @Override
    public String toString() {
        return "ProviderChatRequest[provider=" + provider
                + ", modelId=" + modelId
                + ", prompt=REDACTED, deadline=" + deadline + "]";
    }
}
