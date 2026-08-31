package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

interface ProductChatModelFactory {

    ModelProvider provider();

    /**
     * @param jsonObjectResponse turns on the provider's own JSON-object response
     *     mode. A provider that cannot express it without an unverified request
     *     shape ignores the flag and leaves the reply as text.
     */
    ProductChatModelSession open(
            String credential, String modelId, int maxOutputTokens,
            boolean jsonObjectResponse);
}
