package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderHttpTransport.ProviderHttpResponse;

class ProviderConnectionTestServiceTest {

    private final ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
    private final EncryptedProviderSecretRepository repository = mock(EncryptedProviderSecretRepository.class);
    private final ProviderHttpTransport transport = mock(ProviderHttpTransport.class);
    private final ProviderConnectionTestService service = new ProviderConnectionTestService(
            credentialResolver,
            repository,
            transport,
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-08-11T06:00:00Z"), ZoneOffset.UTC));

    @Test
    void recordsMinimalOpenAiInferenceWithoutReturningContent() throws Exception {
        when(credentialResolver.resolve(ModelProvider.OPENAI))
                .thenReturn(fixtureCredential(ModelProvider.OPENAI));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                200,
                "{\"choices\":[{\"message\":{\"content\":\"OK\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}"));

        ProviderConnectionTestResult result = service.test(ModelProvider.OPENAI);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.VERIFIED);
        assertThat(result.inferenceExecuted()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(5);
        assertThat(result.outputTokens()).isEqualTo(1);
        verify(repository).recordTestIfCurrent(
                eq(ModelProvider.OPENAI),
                eq("fingerprint-OPENAI"),
                eq(result.modelId()),
                eq(ProviderCredentialState.VERIFIED),
                eq("PASSED"),
                isNull(),
                eq(5),
                eq(1),
                eq(0L));
    }

    @Test
    void usesCurrentBoundedGeminiFlashLiteInference() throws Exception {
        when(credentialResolver.resolve(ModelProvider.GOOGLE_GENAI))
                .thenReturn(fixtureCredential(ModelProvider.GOOGLE_GENAI));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":6,\"candidatesTokenCount\":2}}"));

        ProviderConnectionTestResult result = service.test(ModelProvider.GOOGLE_GENAI);

        assertThat(result.modelId()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(result.state()).isEqualTo(ProviderCredentialState.VERIFIED);
        assertThat(result.inferenceExecuted()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(6);
        assertThat(result.outputTokens()).isEqualTo(2);
        verify(repository).recordTestIfCurrent(
                eq(ModelProvider.GOOGLE_GENAI),
                eq("fingerprint-GOOGLE_GENAI"),
                eq(result.modelId()),
                eq(ProviderCredentialState.VERIFIED),
                eq("PASSED"),
                isNull(),
                eq(6),
                eq(2),
                eq(0L));
    }

    @Test
    void classifiesAnthropicBillingWithoutPaidInference() throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                402,
                "{\"error\":{\"type\":\"billing_error\"}}"));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.BILLING_BLOCKED);
        assertThat(result.inferenceExecuted()).isFalse();
        assertThat(result.safeCode()).isEqualTo("BILLING_BLOCKED");
        verify(transport).exchange(
                eq("GET"),
                eq(URI.create("https://api.anthropic.com/v1/models?limit=1")),
                eq(Map.of(
                        "x-api-key", "sk-ant-fixture-only-not-a-real-key-1234567890",
                        "anthropic-version", "2023-06-01")),
                eq(""),
                eq(Duration.ofSeconds(30)));
    }

    @Test
    void classifiesAnthropicLowCreditResponseAsBillingBlocked() throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}"));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.BILLING_BLOCKED);
        assertThat(result.inferenceExecuted()).isFalse();
        assertThat(result.safeCode()).isEqualTo("BILLING_BLOCKED");
    }

    @Test
    void discardsAnOldKeyTestResultAfterTheCredentialIsReplaced() throws Exception {
        when(credentialResolver.resolve(ModelProvider.OPENAI))
                .thenReturn(fixtureCredential(ModelProvider.OPENAI));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                200,
                "{\"choices\":[{\"message\":{\"content\":\"OK\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}"));
        ProviderConnectionTestResult result = service.test(ModelProvider.OPENAI);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.VERIFIED);
        verify(repository).recordTestIfCurrent(
                eq(ModelProvider.OPENAI),
                eq("fingerprint-OPENAI"),
                eq(result.modelId()),
                eq(ProviderCredentialState.VERIFIED),
                eq("PASSED"),
                isNull(),
                eq(5),
                eq(1),
                eq(0L));
    }

    private static ProviderCredentialLease fixtureCredential(ModelProvider provider) {
        String credential = provider == ModelProvider.ANTHROPIC
                ? "sk-ant-fixture-only-not-a-real-key-1234567890"
                : "fixture-only-not-a-real-key";
        return ProviderCredentialLease.fromBytes(
                provider,
                credential.getBytes(StandardCharsets.US_ASCII),
                "fingerprint-" + provider.name());
    }
}
