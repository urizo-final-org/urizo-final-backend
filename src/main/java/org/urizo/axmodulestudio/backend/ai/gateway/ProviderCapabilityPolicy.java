package org.urizo.axmodulestudio.backend.ai.gateway;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class ProviderCapabilityPolicy {

    private final Map<ProviderLane, Map<ModelProvider, Set<ModelCapability>>> matrix;

    private ProviderCapabilityPolicy(Map<ProviderLane, Map<ModelProvider, Set<ModelCapability>>> matrix) {
        this.matrix = Map.copyOf(matrix);
    }

    public static ProviderCapabilityPolicy stage2Baseline() {
        Map<ProviderLane, Map<ModelProvider, Set<ModelCapability>>> matrix = new EnumMap<>(ProviderLane.class);
        matrix.put(ProviderLane.PRODUCT, Map.of(
                ModelProvider.OPENAI, capabilitiesWithEmbedding(),
                ModelProvider.ANTHROPIC, chatCapabilities(),
                ModelProvider.GOOGLE_GENAI, capabilitiesWithEmbedding()));
        matrix.put(ProviderLane.CONTROL, Map.of(
                ModelProvider.OPENAI, capabilitiesWithEmbedding(),
                ModelProvider.ANTHROPIC, chatCapabilities(),
                ModelProvider.VERTEX_AI_GEMINI, capabilitiesWithEmbedding()));
        return new ProviderCapabilityPolicy(matrix);
    }

    public void validate(ProviderLane lane, ProviderModelRegistration registration) {
        Map<ModelProvider, Set<ModelCapability>> lanePolicy = matrix.get(lane);
        Set<ModelCapability> allowed = lanePolicy == null ? null : lanePolicy.get(registration.provider());
        if (allowed == null) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "provider is not supported in the selected Spring AI lane");
        }

        Set<ModelCapability> unsupported = new TreeSet<>(registration.capabilities());
        unsupported.removeAll(allowed);
        if (!unsupported.isEmpty()) {
            String names = unsupported.stream().map(Enum::name).collect(Collectors.joining(","));
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "unsupported model capabilities: " + names);
        }
    }

    private static Set<ModelCapability> chatCapabilities() {
        return Set.of(
                ModelCapability.CHAT,
                ModelCapability.STREAMING,
                ModelCapability.TOOL_CALLING,
                ModelCapability.STRUCTURED_OUTPUT);
    }

    private static Set<ModelCapability> capabilitiesWithEmbedding() {
        return Set.of(
                ModelCapability.CHAT,
                ModelCapability.STREAMING,
                ModelCapability.TOOL_CALLING,
                ModelCapability.STRUCTURED_OUTPUT,
                ModelCapability.EMBEDDING);
    }
}
