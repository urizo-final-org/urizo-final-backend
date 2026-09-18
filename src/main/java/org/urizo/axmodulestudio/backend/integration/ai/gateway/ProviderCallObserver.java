package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import org.urizo.axmodulestudio.backend.integration.ai.observability.ProviderTokenUsage;

import java.util.UUID;

/** Only normalized metadata crosses this boundary; observers must not affect execution. */
public interface ProviderCallObserver {
    default void usage(UUID callId,
            ProviderTokenUsage usage) { }
    ProviderCallObserver NOOP = new ProviderCallObserver() { };

    default UUID started(ModelProvider provider, String model, int attempt) { return null; }
    default void finished(UUID callId, ModelGatewayErrorCode errorCode) { }
}
