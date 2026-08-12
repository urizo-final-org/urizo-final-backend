package org.urizo.axmodulestudio.backend.ai.gateway;

public interface ProviderCredentialResolver {

    ProviderCredentialLease resolve(ModelProvider provider);
}
