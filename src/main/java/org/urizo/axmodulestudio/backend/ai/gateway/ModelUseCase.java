package org.urizo.axmodulestudio.backend.ai.gateway;

import java.util.Set;

public enum ModelUseCase {
    CHAT(Set.of(ModelCapability.CHAT)),
    STREAMING_CHAT(Set.of(ModelCapability.CHAT, ModelCapability.STREAMING)),
    TOOL_CALL(Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING)),
    STRUCTURED_OUTPUT(Set.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT)),
    EMBEDDING(Set.of(ModelCapability.EMBEDDING));

    private final Set<ModelCapability> requiredCapabilities;

    ModelUseCase(Set<ModelCapability> requiredCapabilities) {
        this.requiredCapabilities = Set.copyOf(requiredCapabilities);
    }

    public Set<ModelCapability> requiredCapabilities() {
        return requiredCapabilities;
    }
}
