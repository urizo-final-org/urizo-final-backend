package org.urizo.axmodulestudio.backend.coding.modelturn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderModelRegistration;

class LocalMockProviderChatAdapterTest {

    private final LocalMockProviderChatAdapter adapter = new LocalMockProviderChatAdapter();

    @Test
    void returnsDeterministicResponseWithoutEchoingPrompt() {
        ProviderModelRegistration registration = registration(ModelProvider.OPENAI, "local-model");

        var response = adapter.chat(registration, new ProviderChatRequest(
                ModelProvider.OPENAI,
                "local-model",
                "sensitive local smoke prompt",
                Instant.now().plusSeconds(5)));

        assertThat(response.content()).isEqualTo("LOCAL_MOCK_MODEL_TURN_OK");
        assertThat(response.content()).doesNotContain("sensitive");
        assertThat(response.inputTokens()).isEqualTo(1);
        assertThat(response.outputTokens()).isEqualTo(1);
    }

    @Test
    void rejectsRegistrationMismatch() {
        ProviderModelRegistration registration = registration(ModelProvider.OPENAI, "local-model");
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.GOOGLE_GENAI,
                "local-model",
                "fixture",
                Instant.now().plusSeconds(5));

        assertThatThrownBy(() -> adapter.chat(registration, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderModelRegistration registration(ModelProvider provider, String modelId) {
        return new ProviderModelRegistration(
                provider,
                modelId,
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(5),
                1);
    }
}
