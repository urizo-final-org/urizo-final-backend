package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.*;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;

class ProductProviderFallbackTest {
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC,400,invalid_request_error,,Your credit balance is too low to access the API.,BILLING",
        "ANTHROPIC,400,invalid_request_error,,You have reached your specified API usage limits.,BILLING",
        "OPENAI,401,,,,AUTHENTICATION",
        "ANTHROPIC,401,,,,AUTHENTICATION",
        "GOOGLE_GENAI,400,,,API key not valid. Please pass a valid API key.,AUTHENTICATION",
        "GOOGLE_GENAI,400,,,API key expired. Please renew the API key.,AUTHENTICATION",
        "OPENAI,402,,,,BILLING",
        "OPENAI,429,insufficient_quota,insufficient_quota,,QUOTA",
        "ANTHROPIC,429,rate_limit_error,,,RATE_LIMITED",
        "GOOGLE_GENAI,429,RESOURCE_EXHAUSTED,,,RATE_LIMITED",
        "OPENAI,403,,,,MODEL_ACCESS",
        "ANTHROPIC,404,not_found_error,,model: unknown,MODEL_ACCESS",
        "ANTHROPIC,404,not_found_error,,,INVALID_RESPONSE",
        "OPENAI,404,,model_not_found,,MODEL_ACCESS",
        "GOOGLE_GENAI,404,,,models/missing is not found for API version v1beta.,MODEL_ACCESS",
        "OPENAI,404,,,,INVALID_RESPONSE",
        "ANTHROPIC,529,overloaded_error,,,UNAVAILABLE",
        "OPENAI,500,,,,UNAVAILABLE",
        "GOOGLE_GENAI,503,,,,UNAVAILABLE",
        "GOOGLE_GENAI,504,,,,TIMEOUT",
        "ANTHROPIC,400,invalid_request_error,,invalid tool arguments,BAD_REQUEST",
        "OPENAI,400,,content_policy_violation,,INVALID_RESPONSE",
        "OPENAI,403,,content_policy_violation,,INVALID_RESPONSE",
        "ANTHROPIC,400,invalid_request_error,,Prompt mentions credit balance and billing,INVALID_RESPONSE"
    })
    void classifiesProviderErrorsWithoutKeywordGuessing(ModelProvider provider, int status,
            String type, String code, String message, String expected) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("error", Map.of(
                "type", type == null ? "" : type, "code", code == null ? "" : code,
                "message", message == null ? "secret-error-probe" : message)));
        MockClientHttpResponse response = new MockClientHttpResponse(
                body.getBytes(StandardCharsets.UTF_8), HttpStatusCode.valueOf(status));
        response.getHeaders().set("Retry-After", "2");
        assertThatThrownBy(() -> new ProductProviderErrors(provider).handleError(
                URI.create("https://fixture.invalid"), HttpMethod.POST, response))
            .isInstanceOfSatisfying(ProviderFailure.class, failure -> {
                assertThat(failure.kind()).isEqualTo(ProviderFailureKind.valueOf(
                        expected.equals("BAD_REQUEST") ? "INVALID_RESPONSE" : expected));
                assertThat(failure.getCause()).isNull();
                assertThat(failure.toString()).doesNotContain("secret-error-probe");
                if (failure.kind() == ProviderFailureKind.RATE_LIMITED)
                    assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(2));
            });
    }

    @Test
    void googleSdkCauseIsMappedWithoutRawMessageOrCause() {
        RuntimeException failure = ProductProviderErrors.sanitizeGoogle(new RuntimeException(
                new ClientException(401, "UNAUTHENTICATED", "secret-error-probe")));
        assertThat(failure).isInstanceOfSatisfying(ProviderFailure.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderFailureKind.AUTHENTICATION));
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).doesNotContain("secret-error-probe");
    }

    @ParameterizedTest
    @CsvSource({"OPENAI,ANTHROPIC", "OPENAI,GOOGLE_GENAI", "ANTHROPIC,OPENAI",
            "ANTHROPIC,GOOGLE_GENAI", "GOOGLE_GENAI,OPENAI", "GOOGLE_GENAI,ANTHROPIC"})
    void sixDirectionsUseOnlyPinnedCandidatesAndReturnActualModel(
            ModelProvider primary, ModelProvider fallback) throws Exception {
        ProviderModelRegistration first = registration(primary);
        ProviderModelRegistration second = registration(fallback);
        List<ProviderModelRegistration> pinned = List.of(first, second);
        List<ModelProvider> calls = new ArrayList<>();
        ProviderChatAdapter adapter = new ProviderChatAdapter() {
            public Set<ModelProvider> providers() { return Set.of(primary, fallback); }
            public ProviderChatResponse chat(ProviderModelRegistration model, ProviderChatRequest request) {
                calls.add(model.provider());
                assertThat(request.deadline()).isEqualTo(NOW.plusSeconds(20));
                assertThat(request.messages()).hasSize(1);
                if (model.provider() == primary)
                    throw ProductProviderErrors.classify(primary, 401, "", "", "secret", null);
                return new ProviderChatResponse(fallback, second.modelId(), "OK", 2, 1, Duration.ZERO);
            }
        };
        CodingModelTurnContract.Request request = request();
        String before = new ObjectMapper().findAndRegisterModules().writeValueAsString(request);
        List<String> observed = new ArrayList<>();
        ProviderCallObserver observer = new ProviderCallObserver() {
            public UUID started(ModelProvider provider, String model, int attempt) {
                assertThat(ModelObservationScope.current().turnId()).isEqualTo(request.turnId());
                assertThat(ModelObservationScope.current().executionAttempt()).isEqualTo(request.attempt());
                observed.add(provider.name() + ":" + model);
                return UUID.randomUUID();
            }
            public void finished(UUID id, ModelGatewayErrorCode code) {
                observed.add(code == null ? "SUCCEEDED" : code.name());
            }
        };
        CodingModelTurnContract.Response result = service(pinned, adapter, observer).execute(request, pinned);
        assertThat(observed).containsExactly(primary.name() + ":" + first.modelId(), "MODEL_NOT_CONFIGURED",
                fallback.name() + ":" + second.modelId(), "SUCCEEDED");
        assertThat(ModelObservationScope.current()).isNull();
        assertThat(calls).containsExactly(primary, fallback);
        assertThat(result.selectedModel().modelId()).isEqualTo(second.modelId());
        assertThat(result.selectedModel().provider()).isEqualTo(
                fallback == ModelProvider.GOOGLE_GENAI ? "GOOGLE" : fallback.name());
        assertThat(result.jobId()).isEqualTo(request.jobId());
        assertThat(result.turnId()).isEqualTo(request.turnId());
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(request)).isEqualTo(before);
        assertThat(pinned).containsExactly(first, second);
    }

    @Test
    void exhaustedCandidatesStopAndInvalidOrSafetyResponsesNeverSwitch() {
        List<ProviderModelRegistration> pinned = List.of(
                registration(ModelProvider.ANTHROPIC), registration(ModelProvider.OPENAI),
                registration(ModelProvider.GOOGLE_GENAI));
        ProviderChatAdapter adapter = mock(ProviderChatAdapter.class);
        when(adapter.providers()).thenReturn(Set.of(ModelProvider.ANTHROPIC,
                ModelProvider.OPENAI, ModelProvider.GOOGLE_GENAI));
        when(adapter.chat(any(), any())).thenThrow(
                new ProviderFailure(ProviderFailureKind.BILLING, null));
        assertThatThrownBy(() -> service(pinned, adapter).execute(request(), pinned))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED));
        verify(adapter, times(3)).chat(any(), any());
        clearInvocations(adapter);
        doThrow(new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null))
                .when(adapter).chat(any(), any());
        assertThatThrownBy(() -> service(pinned, adapter).execute(request(), pinned))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID));
        verify(adapter, times(1)).chat(any(), any());
    }

    private static ProviderModelRegistration registration(ModelProvider provider) {
        return new ProviderModelRegistration(provider, "fixture-" + provider.name().toLowerCase(Locale.ROOT),
                Set.of(ModelCapability.CHAT), Duration.ofSeconds(20), 1);
    }

    private static CodingModelTurnService service(List<ProviderModelRegistration> models,
            ProviderChatAdapter adapter) {
        return service(models, adapter, ProviderCallObserver.NOOP);
    }

    private static CodingModelTurnService service(List<ProviderModelRegistration> models,
            ProviderChatAdapter adapter, ProviderCallObserver observer) {
        ProviderCapabilityRegistry registry = new ProviderCapabilityRegistry(ProviderLane.PRODUCT,
                ProviderCapabilityPolicy.stage2Baseline(), models);
        return new CodingModelTurnService(registry, new ProviderChatGateway(registry,
                new ProviderChatAdapterRegistry(List.of(adapter)), CLOCK, observer), new ObjectMapper(), CLOCK, false);
    }

    private static CodingModelTurnContract.Request request() {
        return new CodingModelTurnContract.Request("1.0", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "stage4.model.turn.fixture", 1, 4, "plan", "coding-plan-v1",
                "sha256:" + "b".repeat(64), List.of("CHAT"),
                List.of(JsonNodeFactory.instance.objectNode().put("role", "user").put("content", "fixture")),
                List.of(), JsonNodeFactory.instance.objectNode().put("type", "TEXT"), NOW.plusSeconds(60));
    }
}
