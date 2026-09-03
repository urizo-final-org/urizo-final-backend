package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ProviderModelRegistration(
        ModelProvider provider,
        String modelId,
        Set<ModelCapability> capabilities,
        Duration timeout,
        int maxAttempts,
        int maxOutputTokens,
        InferenceSettings inferenceSettings,
        InferenceSupport inferenceSupport) {

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    public static final int MIN_MAX_OUTPUT_TOKENS = 256;
    public static final int MAX_MAX_OUTPUT_TOKENS = 65_536;
    private static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);

    public ProviderModelRegistration {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities are required"));
        timeout = Objects.requireNonNull(timeout, "timeout is required");
        inferenceSettings = Objects.requireNonNull(inferenceSettings, "inferenceSettings is required");
        inferenceSupport = Objects.requireNonNull(inferenceSupport, "inferenceSupport is required");
        if (!inferenceSupport.supports(inferenceSettings)) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "default inference settings are unsupported");
        }

        if (!MODEL_ID.matcher(modelId).matches()) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "modelId has an invalid format");
        }
        if (capabilities.isEmpty()) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "at least one capability is required");
        }
        if (requiresChat(capabilities) && !capabilities.contains(ModelCapability.CHAT)) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "streaming, tool calling, and structured output require chat capability");
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "timeout must be between one second and two minutes");
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "maxAttempts must be between one and three");
        }
        if (maxOutputTokens < MIN_MAX_OUTPUT_TOKENS
                || maxOutputTokens > MAX_MAX_OUTPUT_TOKENS) {
            throw new CapabilityRegistrationException(
                    ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                    "maxOutputTokens must be between 256 and 65536");
        }
    }

    public ProviderModelRegistration(
            ModelProvider provider,
            String modelId,
            Set<ModelCapability> capabilities,
            Duration timeout,
            int maxAttempts) {
        this(provider, modelId, capabilities, timeout, maxAttempts,
                DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public ProviderModelRegistration(
            ModelProvider provider, String modelId, Set<ModelCapability> capabilities,
            Duration timeout, int maxAttempts, int maxOutputTokens) {
        this(provider, modelId, capabilities, timeout, maxAttempts, maxOutputTokens,
                InferenceSettings.none(), InferenceSupport.disabled());
    }

    public ProviderModelRegistration(
            ModelProvider provider, String modelId, Set<ModelCapability> capabilities,
            Duration timeout, int maxAttempts, int maxOutputTokens,
            InferenceSettings inferenceSettings) {
        this(provider, modelId, capabilities, timeout, maxAttempts, maxOutputTokens,
                inferenceSettings, InferenceSupport.disabled());
    }

    public ProviderModelRegistration withInferenceSettings(InferenceSettings settings) {
        return new ProviderModelRegistration(provider, modelId, capabilities, timeout, maxAttempts,
                maxOutputTokens, settings, inferenceSupport);
    }

    public String selectionId() {
        return provider.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + "-" + modelId.replace('.', '-');
    }

    private static boolean requiresChat(Set<ModelCapability> capabilities) {
        return capabilities.contains(ModelCapability.STREAMING)
                || capabilities.contains(ModelCapability.TOOL_CALLING)
                || capabilities.contains(ModelCapability.STRUCTURED_OUTPUT);
    }
}
