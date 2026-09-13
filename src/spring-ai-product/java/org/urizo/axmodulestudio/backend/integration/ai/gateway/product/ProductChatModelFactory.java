package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

interface ProductChatModelFactory {

    ModelProvider provider();

    ProductChatModelSession open(
            String credential, String modelId, int maxOutputTokens);

    default ProductChatModelSession open(String credential, String modelId,
            int maxOutputTokens, java.time.Duration timeout) {
        return open(credential, modelId, maxOutputTokens);
    }
}
