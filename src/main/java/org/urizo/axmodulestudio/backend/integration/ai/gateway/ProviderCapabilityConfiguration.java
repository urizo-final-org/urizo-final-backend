package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProviderCapabilityConfiguration {

    @Bean
    ProviderCapabilityPolicy providerCapabilityPolicy() {
        return ProviderCapabilityPolicy.stage2Baseline();
    }

    @Bean
    ProviderCapabilityRegistry providerCapabilityRegistry(
            ProviderCapabilityPolicy policy,
            @Value("${ax.ai.provider-lane:PRODUCT}") ProviderLane lane) {
        return new ProviderCapabilityRegistry(lane, policy, registrations(lane));
    }

    @Bean
    ProviderChatAdapterRegistry providerChatAdapterRegistry(List<ProviderChatAdapter> adapters) {
        return new ProviderChatAdapterRegistry(adapters);
    }

    @Bean
    ProviderChatGateway providerChatGateway(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry,
            Clock clock) {
        return new ProviderChatGateway(capabilityRegistry, adapterRegistry, clock);
    }

    static List<ProviderModelRegistration> registrations(ProviderLane lane) {
        if (lane != ProviderLane.PRODUCT) {
            return List.of();
        }
        return List.of(
                new ProviderModelRegistration(
                        ModelProvider.OPENAI,
                        Stage2ProviderModels.OPENAI_CHAT,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        InferenceSettings.none(), InferenceSupport.disabled()),
                new ProviderModelRegistration(
                        ModelProvider.OPENAI,
                        Stage2ProviderModels.OPENAI_TERRA,
                        Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30), 2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        InferenceSettings.none(), new InferenceSupport(InferenceSettings.none(),
                                Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                        InferenceSettings.ReasoningIntensity.MINIMAL,
                                        InferenceSettings.ReasoningIntensity.LOW,
                                        InferenceSettings.ReasoningIntensity.MEDIUM,
                                        InferenceSettings.ReasoningIntensity.HIGH), null)),
                new ProviderModelRegistration(
                        ModelProvider.GOOGLE_GENAI,
                        Stage2ProviderModels.GOOGLE_GENAI_FLASH_3_7,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        new InferenceSettings(InferenceSettings.ReasoningIntensity.MEDIUM, null),
                        new InferenceSupport(
                                new InferenceSettings(
                                        InferenceSettings.ReasoningIntensity.MEDIUM, null),
                                Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                        InferenceSettings.ReasoningIntensity.LOW,
                                        InferenceSettings.ReasoningIntensity.MEDIUM,
                                        InferenceSettings.ReasoningIntensity.HIGH), null)),
                new ProviderModelRegistration(
                        ModelProvider.GOOGLE_GENAI,
                        Stage2ProviderModels.GOOGLE_GENAI_FLASH_3_6,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        new InferenceSettings(InferenceSettings.ReasoningIntensity.MEDIUM, null),
                        new InferenceSupport(
                                new InferenceSettings(
                                        InferenceSettings.ReasoningIntensity.MEDIUM, null),
                                Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                        InferenceSettings.ReasoningIntensity.MINIMAL,
                                        InferenceSettings.ReasoningIntensity.LOW,
                                        InferenceSettings.ReasoningIntensity.MEDIUM,
                                        InferenceSettings.ReasoningIntensity.HIGH), null)),
                new ProviderModelRegistration(
                        ModelProvider.GOOGLE_GENAI,
                        Stage2ProviderModels.GOOGLE_GENAI_CHAT,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        new InferenceSettings(InferenceSettings.ReasoningIntensity.MINIMAL, null),
                        new InferenceSupport(new InferenceSettings(
                                InferenceSettings.ReasoningIntensity.MINIMAL, null),
                                Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                        InferenceSettings.ReasoningIntensity.MINIMAL,
                                        InferenceSettings.ReasoningIntensity.LOW,
                                        InferenceSettings.ReasoningIntensity.MEDIUM,
                                        InferenceSettings.ReasoningIntensity.HIGH), null)),
                new ProviderModelRegistration(
                        ModelProvider.ANTHROPIC,
                        Stage2ProviderModels.ANTHROPIC_OPUS_5,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        InferenceSettings.none(), InferenceSupport.disabled()),
                new ProviderModelRegistration(
                        ModelProvider.ANTHROPIC,
                        Stage2ProviderModels.ANTHROPIC_SONNET_5,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        InferenceSettings.none(), InferenceSupport.disabled()),
                new ProviderModelRegistration(
                        ModelProvider.ANTHROPIC,
                        Stage2ProviderModels.ANTHROPIC_CHAT,
                        Set.of(
                                ModelCapability.CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        Duration.ofSeconds(30),
                        2,
                        ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                        InferenceSettings.none(), new InferenceSupport(InferenceSettings.none(),
                                Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                        InferenceSettings.ReasoningIntensity.HIGH),
                                new InferenceSupport.BudgetRange(1_024, 8_192, 1_024))));
    }
}
