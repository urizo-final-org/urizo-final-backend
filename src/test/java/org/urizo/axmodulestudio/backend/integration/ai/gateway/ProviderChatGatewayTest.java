package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderChatGatewayTest {

    private static final Instant NOW = Instant.parse("2026-08-11T07:00:00Z");

    private final ProviderModelRegistration registration = new ProviderModelRegistration(
            ModelProvider.OPENAI,
            Stage2ProviderModels.OPENAI_CHAT,
            Set.of(ModelCapability.CHAT),
            Duration.ofSeconds(30),
            2);
    private final ProviderChatAdapter adapter = mock(ProviderChatAdapter.class);
    private final List<Duration> retryDelays = new ArrayList<>();
    private ProviderChatGateway gateway;

    @BeforeEach
    void configureAdapter() {
        when(adapter.providers()).thenReturn(Set.of(ModelProvider.OPENAI));
        gateway = new ProviderChatGateway(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                new ProviderChatAdapterRegistry(List.of(adapter)),
                new ProviderErrorNormalizer(),
                new ProviderRetryPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                retryDelays::add);
    }

    @Test
    void routesAnAllowlistedChatRequestAndRedactsContentFromDiagnostics() {
        ProviderChatRequest request = request("local prompt fixture");
        ProviderChatResponse response = response("local response fixture");
        when(adapter.chat(registration, request)).thenReturn(response);

        assertThat(gateway.chat(request)).isEqualTo(response);
        assertThat(request.toString()).doesNotContain("local prompt fixture").contains("REDACTED");
        assertThat(response.toString()).doesNotContain("local response fixture").contains("REDACTED");
    }

    @Test
    void retriesOnlyNormalizedTransientFailuresWithinTheOriginalBudget() {
        ProviderChatRequest request = request("retry fixture");
        ProviderChatResponse response = response("OK");
        when(adapter.chat(registration, request))
                .thenThrow(new ProviderFailure(ProviderFailureKind.TRANSIENT, Duration.ofMillis(10)))
                .thenReturn(response);

        assertThat(gateway.chat(request)).isEqualTo(response);
        assertThat(retryDelays).containsExactly(Duration.ofMillis(10));
        verify(adapter, times(2)).chat(registration, request);
    }

    @Test
    void sanitizesUnexpectedAdapterFailuresWithoutPropagatingRawContent() {
        String rawValue = "raw-provider-or-secret-value-must-not-leak";
        ProviderChatRequest request = request("failure fixture");
        when(adapter.chat(registration, request)).thenThrow(new RuntimeException(rawValue));

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
                    assertThat(failure.getMessage()).isEqualTo("Model provider response failed validation.");
                    assertThat(failure.toString()).doesNotContain(rawValue);
                });
        assertThat(retryDelays).isEmpty();
    }

    @Test
    void rejectsLengthLimitedResponsesInsteadOfTreatingThemAsComplete() {
        ProviderChatRequest request = request("bounded response fixture");
        when(adapter.chat(registration, request)).thenReturn(new ProviderChatResponse(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                "partial output",
                List.of(),
                4,
                8,
                Duration.ofMillis(25),
                ProviderFinishReason.LENGTH_LIMIT));

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(
                            ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
                    assertThat(failure.getMessage()).isEqualTo(
                            "Model provider returned an incomplete response.");
                });
        verify(adapter).chat(registration, request);
    }

    @Test
    void requiresTheExistingToolCallingCapabilityBeforeInvokingAnAdapter() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putArray("required");
        schema.putObject("properties");
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                List.of(ProviderChatMessage.plain(
                        ProviderChatMessage.Role.USER, "Read the approved diff.")),
                List.of(new ProviderToolDefinition(
                        "read_diff", "Read the approved diff.", schema)),
                NOW.plusSeconds(60));

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOfSatisfying(CapabilityRegistrationException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED));
        verify(adapter, times(0)).chat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static ProviderChatRequest request(String prompt) {
        return new ProviderChatRequest(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                prompt,
                NOW.plusSeconds(60));
    }

    private static ProviderChatResponse response(String content) {
        return new ProviderChatResponse(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                content,
                4,
                1,
                Duration.ofMillis(25));
    }
}
