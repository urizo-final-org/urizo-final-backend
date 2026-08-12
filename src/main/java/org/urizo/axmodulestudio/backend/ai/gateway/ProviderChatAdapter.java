package org.urizo.axmodulestudio.backend.ai.gateway;

import java.util.Set;

public interface ProviderChatAdapter {

    Set<ModelProvider> providers();

    ProviderChatResponse chat(
            ProviderModelRegistration registration,
            ProviderChatRequest request);
}
