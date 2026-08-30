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

    private static List<ProviderModelRegistration> registrations(ProviderLane lane) {
        if (lane != ProviderLane.PRODUCT) {
            return List.of();
        }
        return List.of(
                new ProviderModelRegistration(
                        ModelProvider.OPENAI,
                        Stage2ProviderModels.OPENAI_CHAT,
                        Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                        Duration.ofSeconds(30),
                        2),
                new ProviderModelRegistration(
                        ModelProvider.GOOGLE_GENAI,
                        Stage2ProviderModels.GOOGLE_GENAI_CHAT,
                        Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                        Duration.ofSeconds(30),
                        2));
    }
}
