package org.urizo.axmodulestudio.backend.integration.ai.gateway;

@FunctionalInterface
public interface ProviderChatGatewayPort {

    ProviderChatResponse chat(ProviderChatRequest request);
}
