package org.urizo.axmodulestudio.backend.ai.gateway;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProviderChatAdapterRegistry {

    private final Map<ModelProvider, ProviderChatAdapter> adapters;

    public ProviderChatAdapterRegistry(List<ProviderChatAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters are required");
        Map<ModelProvider, ProviderChatAdapter> indexed = new EnumMap<>(ModelProvider.class);
        for (ProviderChatAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter is required");
            if (adapter.providers() == null || adapter.providers().isEmpty()) {
                throw new IllegalArgumentException("Provider chat adapter must declare at least one provider.");
            }
            for (ModelProvider provider : adapter.providers()) {
                if (indexed.putIfAbsent(provider, adapter) != null) {
                    throw new IllegalArgumentException("Duplicate provider chat adapter registration.");
                }
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    public ProviderChatAdapter require(ModelProvider provider) {
        ProviderChatAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                    "Provider chat adapter is not configured.");
        }
        return adapter;
    }
}
