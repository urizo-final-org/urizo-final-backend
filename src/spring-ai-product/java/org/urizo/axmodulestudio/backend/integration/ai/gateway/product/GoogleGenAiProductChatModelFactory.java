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

    @Override
    public ModelProvider provider() {
        return ModelProvider.GOOGLE_GENAI;
    }

    @Override
    public ProductChatModelSession open(String credential, String modelId) {
        Client client = Client.builder()
                .apiKey(credential)
                .vertexAI(false)
                .build();
        try {
            GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                    .model(modelId)
                    .maxOutputTokens(512)
                    .internalToolExecutionEnabled(false)
                    .build();
            GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                    .genAiClient(client)
                    .defaultOptions(options)
                    .retryTemplate(singleAttempt())
                    .observationRegistry(ObservationRegistry.NOOP)
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
