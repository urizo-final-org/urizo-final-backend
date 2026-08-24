package org.urizo.axmodulestudio.backend.integration.ai.gateway;

public interface ProviderProbeClient {

    ModelProvider provider();

    ProviderProbeResult probe(ProviderProbeRequest request);
}
