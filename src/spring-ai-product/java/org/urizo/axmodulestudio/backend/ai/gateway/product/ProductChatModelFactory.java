package org.urizo.axmodulestudio.backend.ai.gateway.product;

import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;

interface ProductChatModelFactory {

    ModelProvider provider();

    ProductChatModelSession open(String credential, String modelId);
}
