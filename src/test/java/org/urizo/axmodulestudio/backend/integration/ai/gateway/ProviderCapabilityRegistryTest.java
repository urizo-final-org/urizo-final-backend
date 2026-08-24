package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ProviderCapabilityRegistryTest {

    private final ProviderCapabilityPolicy policy = ProviderCapabilityPolicy.stage2Baseline();

    @Test
    void productLaneSelectsOnlyModelsThatSatisfyTheAtomicUseCase() {
        ProviderModelRegistration openAi = registration(
                ModelProvider.OPENAI,
                "gpt-stage2",
                Set.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT, ModelCapability.EMBEDDING));
        ProviderModelRegistration anthropic = registration(
                ModelProvider.ANTHROPIC,
                "claude-stage2",
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING));
        ProviderModelRegistration google = registration(
                ModelProvider.GOOGLE_GENAI,
                "gemini-stage2",
                Set.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.EMBEDDING));
        ProviderCapabilityRegistry registry = new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                policy,
                List.of(openAi, anthropic, google));

        assertThat(registry.require(ModelProvider.ANTHROPIC, "claude-stage2", ModelUseCase.TOOL_CALL))
                .isSameAs(anthropic);
        assertThat(registry.candidates(ModelUseCase.EMBEDDING))
                .extracting(ProviderModelRegistration::provider)
                .containsExactly(ModelProvider.GOOGLE_GENAI, ModelProvider.OPENAI);
        assertThat(registry.candidates(ModelUseCase.STRUCTURED_OUTPUT))
                .containsExactly(openAi);
    }

    @Test
    void unsupportedProviderIsRejectedAtRegistrationTime() {
        ProviderModelRegistration google = registration(
                ModelProvider.GOOGLE_GENAI,
                "gemini-stage2",
                Set.of(ModelCapability.CHAT));

        assertThatThrownBy(() -> new ProviderCapabilityRegistry(
                ProviderLane.CONTROL,
                policy,
                List.of(google)))
                .isInstanceOf(CapabilityRegistrationException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED);
    }

    @Test
    void unsupportedCapabilityIsRejectedAtRegistrationTime() {
        ProviderModelRegistration anthropicEmbedding = registration(
                ModelProvider.ANTHROPIC,
                "claude-stage2",
                Set.of(ModelCapability.EMBEDDING));

        assertThatThrownBy(() -> new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                policy,
                List.of(anthropicEmbedding)))
                .isInstanceOf(CapabilityRegistrationException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED);
    }

    @Test
    void configuredModelStillRejectsAnUnsupportedUseCase() {
        ProviderModelRegistration chatOnly = registration(
                ModelProvider.OPENAI,
                "chat-only",
                Set.of(ModelCapability.CHAT));
        ProviderCapabilityRegistry registry = new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                policy,
                List.of(chatOnly));

        assertThatThrownBy(() -> registry.require(
                ModelProvider.OPENAI,
                "chat-only",
                ModelUseCase.STRUCTURED_OUTPUT))
                .isInstanceOf(CapabilityRegistrationException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED);
    }

    @Test
    void duplicateProviderModelRegistrationIsRejected() {
        ProviderModelRegistration first = registration(
                ModelProvider.OPENAI,
                "duplicate",
                Set.of(ModelCapability.CHAT));
        ProviderModelRegistration second = registration(
                ModelProvider.OPENAI,
                "duplicate",
                Set.of(ModelCapability.CHAT, ModelCapability.STREAMING));

        assertThatThrownBy(() -> new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                policy,
                List.of(first, second)))
                .isInstanceOf(CapabilityRegistrationException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED);
    }

    @Test
    void derivedChatCapabilitiesCannotBeDeclaredWithoutChat() {
        assertThatThrownBy(() -> registration(
                ModelProvider.OPENAI,
                "invalid",
                Set.of(ModelCapability.TOOL_CALLING)))
                .isInstanceOf(CapabilityRegistrationException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED);
    }

    @Test
    void registrationContractHasNoRawCredentialField() {
        assertThat(Stream.of(ProviderModelRegistration.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase))
                .noneMatch(name -> name.contains("secret") || name.contains("token") || name.contains("key"));
    }

    private static ProviderModelRegistration registration(
            ModelProvider provider,
            String modelId,
            Set<ModelCapability> capabilities) {
        return new ProviderModelRegistration(provider, modelId, capabilities, Duration.ofSeconds(30), 3);
    }
}
