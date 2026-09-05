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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void recordsAnthropicOnlyAfterARealMinimalInference() throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],"
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}"));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.modelId()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(result.state()).isEqualTo(ProviderCredentialState.VERIFIED);
        assertThat(result.inferenceExecuted()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(7);
        assertThat(result.outputTokens()).isEqualTo(1);
        verify(transport).exchange(
                eq("POST"),
                eq(URI.create("https://api.anthropic.com/v1/messages")),
                eq(Map.of(
                        "x-api-key", "sk-ant-fixture-only-not-a-real-key-1234567890",
                        "anthropic-version", "2023-06-01",
                        "Content-Type", "application/json")),
                eq("{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":8,"
                        + "\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Reply with exactly OK.\"}]}"),
                eq(Duration.ofSeconds(30)));
        verify(repository).recordTestIfCurrent(
                eq(ModelProvider.ANTHROPIC),
                eq("fingerprint-ANTHROPIC"),
                eq(result.modelId()),
                eq(ProviderCredentialState.VERIFIED),
                eq("PASSED"),
                isNull(),
                eq(7),
                eq(1),
                eq(0L));
    }

    @Test
    void doesNotVerifyAnthropicWhenInferenceReturnsNoOutput() throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                200,
                "{\"content\":[],\"usage\":{\"input_tokens\":7,\"output_tokens\":0}}"));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.PROVIDER_UNAVAILABLE);
        assertThat(result.inferenceExecuted()).isFalse();
        assertThat(result.safeCode()).isEqualTo("MODEL_RESPONSE_INVALID");
    }

    @Test
    void classifiesAnthropicBillingDuringMinimalInference() throws Exception {
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
                eq("POST"),
                eq(URI.create("https://api.anthropic.com/v1/messages")),
                eq(Map.of(
                        "x-api-key", "sk-ant-fixture-only-not-a-real-key-1234567890",
                        "anthropic-version", "2023-06-01",
                        "Content-Type", "application/json")),
                any(String.class),
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

    @ParameterizedTest
    @ValueSource(strings = {
            "Billing is not enabled for this workspace.",
            "Insufficient credits are available.",
            "The organization spend cap was reached.",
            "The workspace usage-limit was reached."
    })
    void classifiesAnthropicBillingMessageFamiliesWithoutReturningTheMessage(String message) throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                400,
                anthropicInvalidRequest(message)));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.BILLING_BLOCKED);
        assertThat(result.inferenceExecuted()).isFalse();
        assertThat(result.safeCode()).isEqualTo("BILLING_BLOCKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "anthropic-workspace-id header is required.",
            "Workspace ID must be provided.",
            "Missing workspace_id for this request."
    })
    void classifiesAnthropicWorkspaceIdRequirementsWithoutReturningTheMessage(String message) throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                400,
                anthropicInvalidRequest(message)));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.PROVIDER_UNAVAILABLE);
        assertThat(result.inferenceExecuted()).isFalse();
        assertThat(result.safeCode()).isEqualTo("WORKSPACE_ID_REQUIRED");
        verify(repository).recordTestIfCurrent(
                eq(ModelProvider.ANTHROPIC),
                eq("fingerprint-ANTHROPIC"),
                eq(result.modelId()),
                eq(ProviderCredentialState.PROVIDER_UNAVAILABLE),
                eq("FAILED"),
                eq("WORKSPACE_ID_REQUIRED"),
                isNull(),
                isNull(),
                eq(0L));
    }

    @Test
    void preservesOtherAnthropicInvalidRequestsAsSafeProviderCodes() throws Exception {
        when(credentialResolver.resolve(ModelProvider.ANTHROPIC))
                .thenReturn(fixtureCredential(ModelProvider.ANTHROPIC));
        when(transport.exchange(any(), any(), any(), any(), any())).thenReturn(new ProviderHttpResponse(
                400,
                anthropicInvalidRequest("messages.0.role has an unsupported value.")));

        ProviderConnectionTestResult result = service.test(ModelProvider.ANTHROPIC);

        assertThat(result.state()).isEqualTo(ProviderCredentialState.PROVIDER_UNAVAILABLE);
        assertThat(result.inferenceExecuted()).isFalse();
        assertThat(result.safeCode()).isEqualTo("INVALID_REQUEST_ERROR");
        verify(repository).recordTestIfCurrent(
                eq(ModelProvider.ANTHROPIC),
                eq("fingerprint-ANTHROPIC"),
                eq(result.modelId()),
                eq(ProviderCredentialState.PROVIDER_UNAVAILABLE),
                eq("FAILED"),
                eq("INVALID_REQUEST_ERROR"),
                isNull(),
                isNull(),
                eq(0L));
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

    private static String anthropicInvalidRequest(String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"" + message + "\"}}";
    }
}
