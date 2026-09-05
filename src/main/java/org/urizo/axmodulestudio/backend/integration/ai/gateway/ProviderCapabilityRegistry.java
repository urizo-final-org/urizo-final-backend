package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProviderCapabilityRegistry {

    private final Map<ModelKey, ProviderModelRegistration> registrations;

    public ProviderCapabilityRegistry(
            ProviderLane lane,
            ProviderCapabilityPolicy policy,
            Collection<ProviderModelRegistration> registrations) {
        Objects.requireNonNull(lane, "lane is required");
        Objects.requireNonNull(policy, "policy is required");
        Objects.requireNonNull(registrations, "registrations are required");

        Map<ModelKey, ProviderModelRegistration> validated = new LinkedHashMap<>();
        for (ProviderModelRegistration registration : registrations) {
            policy.validate(lane, registration);
            ModelKey key = new ModelKey(registration.provider(), registration.modelId());
            if (validated.putIfAbsent(key, registration) != null) {
                throw new CapabilityRegistrationException(
                        ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                        "duplicate provider model registration");
            }
        }
        this.registrations = Map.copyOf(validated);
    }

    public ProviderModelRegistration require(
            ModelProvider provider,
            String modelId,
            ModelUseCase useCase) {
        ProviderModelRegistration registration = registrations.get(new ModelKey(provider, modelId));
        if (registration == null) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                    "provider model is not configured");
        }
        if (!registration.capabilities().containsAll(useCase.requiredCapabilities())) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "provider model does not satisfy the requested use case");
        }
        return registration;
    }

    public List<ProviderModelRegistration> candidates(ModelUseCase useCase) {
        return registrations.values().stream()
                .filter(registration -> registration.capabilities().containsAll(useCase.requiredCapabilities()))
                .sorted(Comparator
                        .comparingInt((ProviderModelRegistration value) ->
                                defaultSelectionOrder(value.provider()))
                        .thenComparing(value -> value.provider().name())
                        .thenComparing(ProviderModelRegistration::modelId))
                .toList();
    }

    public List<ProviderModelRegistration> registrations() {
        return registrations.values().stream()
                .sorted(Comparator.comparing(value -> value.provider().name() + ":" + value.modelId()))
                .toList();
    }

    public ProviderModelRegistration findBySelectionId(String selectionId) {
        if (selectionId == null) return null;
        return registrations.values().stream()
                .filter(registration -> selectionId.equals(registration.selectionId()))
                .findFirst().orElse(null);
    }

    private static int defaultSelectionOrder(ModelProvider provider) {
        return provider == ModelProvider.ANTHROPIC ? 1 : 0;
    }

    private record ModelKey(ModelProvider provider, String modelId) {
        private ModelKey {
            Objects.requireNonNull(provider, "provider is required");
            Objects.requireNonNull(modelId, "modelId is required");
        }
    }
}
