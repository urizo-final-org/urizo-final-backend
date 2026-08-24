package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ProviderProbeClientRegistry {

    private final Map<ModelProvider, ProviderProbeClient> clients;

    public ProviderProbeClientRegistry(Collection<ProviderProbeClient> clients) {
        Objects.requireNonNull(clients, "clients are required");
        Map<ModelProvider, ProviderProbeClient> validated = new EnumMap<>(ModelProvider.class);
        for (ProviderProbeClient client : clients) {
            Objects.requireNonNull(client, "provider probe client is required");
            ModelProvider provider = Objects.requireNonNull(client.provider(), "client provider is required");
            if (validated.putIfAbsent(provider, client) != null) {
                throw new CapabilityRegistrationException(
                        ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                        "duplicate provider probe client");
            }
        }
        this.clients = Map.copyOf(validated);
    }

    public ProviderProbeClient require(ModelProvider provider) {
        Objects.requireNonNull(provider, "provider is required");
        ProviderProbeClient client = clients.get(provider);
        if (client == null) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                    "provider connection client is not configured");
        }
        return client;
    }

    public boolean supports(ModelProvider provider) {
        return clients.containsKey(Objects.requireNonNull(provider, "provider is required"));
    }
}
