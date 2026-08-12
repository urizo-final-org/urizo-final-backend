package org.urizo.axmodulestudio.backend.dev.cms;

import java.util.Arrays;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderGatewayException;

@Component
@Profile("dev")
public final class CmsProviderCredentialResolver implements ProviderCredentialResolver {

    private final LocalProviderSecretService secretService;

    public CmsProviderCredentialResolver(LocalProviderSecretService secretService) {
        this.secretService = secretService;
    }

    @Override
    public ProviderCredentialLease resolve(ModelProvider provider) {
        byte[] plaintext = null;
        try {
            plaintext = secretService.decryptForProviderCall(provider);
            return ProviderCredentialLease.fromBytes(provider, plaintext);
        }
        catch (IllegalArgumentException failure) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                    "Provider credential is not configured.");
        }
        catch (RuntimeException failure) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_PROVIDER_UNAVAILABLE,
                    "Provider credential could not be resolved.");
        }
        finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }
}
