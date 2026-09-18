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
    void persistsReportedUsageBeforeRejectingIncompleteResponsesAndIgnoresObserverFailure() {
        var observer = mock(ProviderCallObserver.class);
        var id = java.util.UUID.randomUUID();
        when(observer.started(ModelProvider.OPENAI, registration.modelId(), 1)).thenReturn(id);
        var observed = new org.urizo.axmodulestudio.backend.integration.ai.observability.ProviderTokenUsage(100, 8, 40);
        var request = request("fixture");
        gateway = new ProviderChatGateway(new ProviderCapabilityRegistry(ProviderLane.PRODUCT,
                ProviderCapabilityPolicy.stage2Baseline(), List.of(registration)),
                new ProviderChatAdapterRegistry(List.of(adapter)), new ProviderErrorNormalizer(),
                new ProviderRetryPolicy(), Clock.fixed(NOW, ZoneOffset.UTC), retryDelays::add, observer);
        when(adapter.chat(registration, request)).thenReturn(new ProviderChatResponse(ModelProvider.OPENAI,
                registration.modelId(), "partial", List.of(), 100, 8, Duration.ofMillis(25), ProviderFinishReason.LENGTH_LIMIT, observed));
        org.mockito.Mockito.doThrow(new RuntimeException("store unavailable")).when(observer).usage(id, observed);
        assertThatThrownBy(() -> gateway.chat(request)).isInstanceOfSatisfying(ProviderGatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID));
        var order = org.mockito.Mockito.inOrder(observer);
        order.verify(observer).started(ModelProvider.OPENAI, registration.modelId(), 1);
        order.verify(observer).usage(id, observed);
        order.verify(observer).finished(id, ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
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
                    // The ending itself, not only that there was one. Every non-"stop"
                    // ending shares this code, and the stored turn keeps the code alone,
                    // so the message is the only place the difference survives.
                    assertThat(failure.getMessage())
                            .startsWith("Model provider returned an incomplete response:")
                            .contains("LENGTH_LIMIT");
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
                NOW.plusSeconds(30));

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOfSatisfying(CapabilityRegistrationException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED));
        verify(adapter, times(0)).chat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }


    @Test
    void passesTheProviderDeadlineToTheTransportAndRejectsLateSuccess() {
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        ProviderChatGateway bounded = new ProviderChatGateway(
                new ProviderCapabilityRegistry(ProviderLane.PRODUCT, ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)), new ProviderChatAdapterRegistry(List.of(adapter)), moving);
        ProviderChatRequest original = new ProviderChatRequest(ModelProvider.OPENAI,
                registration.modelId(), "fixture", NOW.plusSeconds(60));
        when(adapter.chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ProviderChatRequest sent = invocation.getArgument(1);
                    assertThat(sent.deadline()).isEqualTo(NOW.plusSeconds(30));
                    now.set(NOW.plusSeconds(31));
                    return response("late");
                });
        assertThatThrownBy(() -> bounded.chat(original)).isInstanceOfSatisfying(
                ProviderGatewayException.class,
                error -> assertThat(error.code()).isEqualTo(ModelGatewayErrorCode.MODEL_TIMEOUT));
        verify(adapter, times(1)).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exhaustedRetryBudgetAndPermanentErrorsHaveBoundedCalls() {
        ProviderChatRequest request = request("fixture");
        when(adapter.chat(registration, request)).thenThrow(
                new ProviderFailure(ProviderFailureKind.RATE_LIMITED, Duration.ofMillis(1)));
        assertThatThrownBy(() -> gateway.chat(request)).isInstanceOf(ProviderGatewayException.class);
        verify(adapter, times(2)).chat(registration, request);
        assertThat(retryDelays).containsExactly(Duration.ofMillis(1));
        org.mockito.Mockito.clearInvocations(adapter);
        retryDelays.clear();
        org.mockito.Mockito.doThrow(new ProviderFailure(ProviderFailureKind.BILLING, null))
                .when(adapter).chat(registration, request);
        assertThatThrownBy(() -> gateway.chat(request)).isInstanceOf(ProviderGatewayException.class);
        verify(adapter, times(1)).chat(registration, request);
        assertThat(retryDelays).isEmpty();
    }

    @Test
    void interruptedExecutionDoesNotMakeAnotherProviderCall() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gateway.chat(request("fixture")))
                    .isInstanceOf(ProviderGatewayException.class);
            verify(adapter, times(0)).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }


    private static ProviderChatRequest request(String prompt) {
        return new ProviderChatRequest(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                prompt,
                NOW.plusSeconds(30));
    }

    @Test
    void recordsActualRetryOrderAndOnlyNormalizedFailures() {
        List<String> events = new ArrayList<>();
        ProviderCallObserver observer = new ProviderCallObserver() {
            public java.util.UUID started(ModelProvider provider, String model, int attempt) {
                events.add(provider + ":" + model + ":" + attempt);
                return java.util.UUID.randomUUID();
            }
            public void finished(java.util.UUID id, ModelGatewayErrorCode error) {
                events.add(error == null ? "SUCCESS" : error.name());
            }
        };
        ProviderChatGateway observed = observed(observer);
        when(adapter.chat(registration, request("fixture")))
                .thenThrow(new ProviderFailure(ProviderFailureKind.RATE_LIMITED, Duration.ofMillis(1)))
                .thenReturn(response("private response"));
        observed.chat(request("fixture"));
        assertThat(events).containsExactly("OPENAI:" + registration.modelId() + ":1", "MODEL_RATE_LIMITED",
                "OPENAI:" + registration.modelId() + ":2", "SUCCESS");
    }

    @Test
    void observerFailureNeverChangesSuccessOrOriginalFailure() {
        ProviderCallObserver observer = new ProviderCallObserver() {
            public java.util.UUID started(ModelProvider provider, String model, int attempt) {
                throw new IllegalStateException("store unavailable");
            }
        };
        when(adapter.chat(registration, request("fixture"))).thenReturn(response("OK"));
        assertThat(observed(observer).chat(request("fixture")).content()).isEqualTo("OK");
        org.mockito.Mockito.doThrow(new ProviderFailure(ProviderFailureKind.BILLING, null))
                .when(adapter).chat(registration, request("fixture"));
        assertThatThrownBy(() -> observed(observer).chat(request("fixture")))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED));
    }

    @Test
    void completionObserverFailureDoesNotRetrySuccessfulProvider() {
        ProviderCallObserver observer = new ProviderCallObserver() {
            public java.util.UUID started(ModelProvider provider, String model, int attempt) {
                return java.util.UUID.randomUUID();
            }
            public void finished(java.util.UUID id, ModelGatewayErrorCode error) {
                throw new IllegalStateException("store unavailable");
            }
        };
        when(adapter.chat(registration, request("fixture"))).thenReturn(response("OK"));
        assertThat(observed(observer).chat(request("fixture")).content()).isEqualTo("OK");
        verify(adapter, times(1)).chat(registration, request("fixture"));
    }

    private ProviderChatGateway observed(ProviderCallObserver observer) {
        return new ProviderChatGateway(new ProviderCapabilityRegistry(ProviderLane.PRODUCT,
                ProviderCapabilityPolicy.stage2Baseline(), List.of(registration)),
                new ProviderChatAdapterRegistry(List.of(adapter)), new ProviderErrorNormalizer(),
                new ProviderRetryPolicy(), Clock.fixed(NOW, ZoneOffset.UTC), retryDelays::add, observer);
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
