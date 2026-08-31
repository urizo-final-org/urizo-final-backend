package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

@Component
final class AnthropicProductChatModelFactory implements ProductChatModelFactory {

    @Override
    public ModelProvider provider() {
        return ModelProvider.ANTHROPIC;
    }

    /**
     * Anthropic keeps its structured output behind the beta that
     * {@code AnthropicApi.DEFAULT_ANTHROPIC_BETA_VERSION} names, and its
     * {@code OutputFormat} record takes a free-form type string with no constant to
     * follow. Guessing that string is not something a local credential can verify
     * here, so this provider leaves the reply as text and relies on the caller's
     * repair and re-ask passes. Anthropic is also last in the selection order.
     */
    @Override
    public ProductChatModelSession open(
            String credential, String modelId, int maxOutputTokens,
            boolean jsonObjectResponse) {
        AnthropicApi api = AnthropicApi.builder()
                .apiKey(credential)
                .build();
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(modelId)
                .maxTokens(maxOutputTokens)
                .internalToolExecutionEnabled(false)
                .build();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .retryTemplate(singleAttempt())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        return new ProductChatModelSession(model, () -> { });
    }

    private static RetryTemplate singleAttempt() {
        return RetryTemplate.builder().maxAttempts(1).noBackoff().build();
    }
}
