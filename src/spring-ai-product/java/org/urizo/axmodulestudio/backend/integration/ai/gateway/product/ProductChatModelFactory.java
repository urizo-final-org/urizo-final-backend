package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

interface ProductChatModelFactory {

    ModelProvider provider();

    ProductChatModelSession open(String credential, String modelId);
}
