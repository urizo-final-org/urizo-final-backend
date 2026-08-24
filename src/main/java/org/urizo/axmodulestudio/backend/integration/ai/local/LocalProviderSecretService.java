package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderSecretCrypto.EncryptedSecret;

@Service
@Profile("dev")
public class LocalProviderSecretService {

    private static final Set<ModelProvider> SUPPORTED_PROVIDERS = EnumSet.of(
            ModelProvider.OPENAI,
            ModelProvider.ANTHROPIC,
            ModelProvider.GOOGLE_GENAI);

    private final EncryptedProviderSecretRepository repository;
    private final ProviderSecretCrypto crypto;

    public LocalProviderSecretService(
            EncryptedProviderSecretRepository repository,
            ProviderSecretCrypto crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    public List<ProviderCredentialStatus> statuses() {
        Map<ModelProvider, StoredProviderSecret> configured = repository.findAll().stream()
                .collect(Collectors.toMap(StoredProviderSecret::provider, Function.identity()));
        return SUPPORTED_PROVIDERS.stream()
                .sorted()
                .map(provider -> status(provider, configured.get(provider)))
                .toList();
    }

    public ProviderCredentialStatus store(ModelProvider provider, String credential) {
        requireSupported(provider);
        validateCredential(credential);

        byte[] plaintext = credential.getBytes(StandardCharsets.US_ASCII);
        try {
            EncryptedSecret encryptedSecret = crypto.encrypt(provider, plaintext);
            repository.upsert(provider, encryptedSecret);
            return status(provider, repository.find(provider).orElseThrow());
        }
        finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    byte[] decryptForProviderCall(ModelProvider provider) {
        requireSupported(provider);
        StoredProviderSecret stored = repository.find(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider credential is not configured."));
        return crypto.decrypt(provider, stored.encryptedSecret());
    }

    public void updateState(ModelProvider provider, ProviderCredentialState state) {
        requireSupported(provider);
        repository.updateState(provider, state);
    }

    private static ProviderCredentialStatus status(ModelProvider provider, StoredProviderSecret stored) {
        if (stored == null) {
            return ProviderCredentialStatus.notConfigured(provider);
        }
        String fingerprint = stored.encryptedSecret().fingerprint();
        String suffix = fingerprint.substring(Math.max(0, fingerprint.length() - 12));
        return new ProviderCredentialStatus(
                provider,
                true,
                stored.state(),
                suffix,
                stored.updatedAt(),
                stored.lastTestedAt());
    }

    private static void validateCredential(String credential) {
        if (credential == null || credential.length() < 8 || credential.length() > 4096) {
            throw new IllegalArgumentException("Credential length must be between 8 and 4096 characters.");
        }
        if (!credential.equals(credential.strip())) {
            throw new IllegalArgumentException("Credential cannot contain leading or trailing whitespace.");
        }
        boolean printableAscii = credential.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
        if (!printableAscii) {
            throw new IllegalArgumentException("Credential must contain printable ASCII characters only.");
        }
    }

    private static void requireSupported(ModelProvider provider) {
        if (provider == null || !SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Provider is not supported by the local CMS.");
        }
    }
}
