package org.urizo.axmodulestudio.backend.coding.integration;

import java.time.Duration;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatAdapter;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;

@Component
@Profile("dev & coding-model-turn-local-mock")
final class LocalMockProviderChatAdapter implements ProviderChatAdapter {

    private static final Set<ModelProvider> PROVIDERS = Set.of(
            ModelProvider.OPENAI,
            ModelProvider.ANTHROPIC,
            ModelProvider.GOOGLE_GENAI);

    @Override
    public Set<ModelProvider> providers() {
        return PROVIDERS;
    }

    @Override
    public ProviderChatResponse chat(
            ProviderModelRegistration registration,
            ProviderChatRequest request) {
        if (registration.provider() != request.provider()
                || !registration.modelId().equals(request.modelId())
                || !PROVIDERS.contains(request.provider())) {
            throw new IllegalArgumentException("Local mock registration does not match the request.");
        }
        return new ProviderChatResponse(
                request.provider(),
                request.modelId(),
                "LOCAL_MOCK_MODEL_TURN_OK",
                1,
                1,
                Duration.ZERO);
    }
}
