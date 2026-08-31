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

    @Override
    public ProductChatModelSession open(
            String credential, String modelId, int maxOutputTokens) {
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
