package org.urizo.axmodulestudio.backend.ai.gateway;

@FunctionalInterface
public interface ProviderChatGatewayPort {

    ProviderChatResponse chat(ProviderChatRequest request);
}
