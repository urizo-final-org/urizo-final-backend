package org.urizo.axmodulestudio.backend.ai.gateway.product;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;

@Component
final class OpenAiProductChatModelFactory implements ProductChatModelFactory {

    @Override
    public ModelProvider provider() {
        return ModelProvider.OPENAI;
    }

    @Override
    public ProductChatModelSession open(String credential, String modelId) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(credential)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelId)
                .maxCompletionTokens(512)
                .internalToolExecutionEnabled(false)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
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
