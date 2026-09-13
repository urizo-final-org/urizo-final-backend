package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

@Component
final class GoogleGenAiProductChatModelFactory implements ProductChatModelFactory {

    private final ObservationRegistry observationRegistry;

    GoogleGenAiProductChatModelFactory(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.GOOGLE_GENAI;
    }

    @Override
    public ProductChatModelSession open(
            String credential, String modelId, int maxOutputTokens) {
        return open(credential, modelId, maxOutputTokens, java.time.Duration.ofSeconds(60));
    }

    @Override
    public ProductChatModelSession open(String credential, String modelId, int maxOutputTokens,
            java.time.Duration timeout) {
        Client client = Client.builder()
                .apiKey(credential)
                .httpOptions(com.google.genai.types.HttpOptions.builder()
                        .timeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis())))
                        .retryOptions(com.google.genai.types.HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .vertexAI(false)
                .build();
        try {
            GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                    .model(modelId)
                    .maxOutputTokens(maxOutputTokens)
                    .internalToolExecutionEnabled(false)
                    .build();
            GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                    .genAiClient(client)
                    .defaultOptions(options)
                    .retryTemplate(singleAttempt())
                    .observationRegistry(observationRegistry)
                    .build();
            return new ProductChatModelSession(model, client::close);
        }
        catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    private static RetryTemplate singleAttempt() {
        return RetryTemplate.builder().maxAttempts(1).noBackoff().build();
    }
}
