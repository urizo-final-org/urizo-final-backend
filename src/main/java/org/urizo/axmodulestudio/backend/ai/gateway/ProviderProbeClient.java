package org.urizo.axmodulestudio.backend.ai.gateway;

public interface ProviderProbeClient {

    ModelProvider provider();

    ProviderProbeResult probe(ProviderProbeRequest request);
}
