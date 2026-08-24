package org.urizo.axmodulestudio.backend.integration.ai.gateway;

public interface ProviderCredentialResolver {

    ProviderCredentialLease resolve(ModelProvider provider);
}
