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

    private final ObservationRegistry observationRegistry;

    AnthropicProductChatModelFactory(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.ANTHROPIC;
    }

    @Override
    public ProductChatModelSession open(
            String credential, String modelId, int maxOutputTokens) {
        return open(credential, modelId, maxOutputTokens, java.time.Duration.ofSeconds(60));
    }

    @Override
    public ProductChatModelSession open(String credential, String modelId, int maxOutputTokens,
            java.time.Duration timeout) {
        org.springframework.http.client.SimpleClientHttpRequestFactory http =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        http.setConnectTimeout(timeoutMillis);
        http.setReadTimeout(timeoutMillis);
        AnthropicApi api = AnthropicApi.builder()
                .apiKey(credential)
                .restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(http))
                .responseErrorHandler(new ProductProviderErrors(provider()))
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
                .observationRegistry(observationRegistry)
                .build();
        return new ProductChatModelSession(model, () -> { });
    }

    private static RetryTemplate singleAttempt() {
        return RetryTemplate.builder().maxAttempts(1).noBackoff().build();
    }
}
