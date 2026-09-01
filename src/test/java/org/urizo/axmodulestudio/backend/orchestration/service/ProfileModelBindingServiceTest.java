package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

class ProfileModelBindingServiceTest {

    private static final UUID PROFILE =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID NEXT_PROFILE =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsOnlyTheJobFrozenProfileVersionBeforeResolvingTheNode() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ProviderCapabilityRegistry registry = registry(
                registration(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT));
        JsonNode snapshot = snapshot("""
                {
                  "analyze":{"primary":"llm-ops-analyze","fallback":[]},
                  "review":{"primary":"llm-ops-review","fallback":[]}
                }
                """);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(PROFILE)))
                .thenReturn(objectMapper.writeValueAsString(snapshot));
        ProfileModelBindingService service =
                new ProfileModelBindingService(jdbcTemplate, objectMapper, registry);

        assertThat(service.resolve(
                PROFILE, "analyze", "coding.analyze", ModelUseCase.CHAT))
                .singleElement()
                .satisfies(model -> assertThat(model.provider())
                        .isEqualTo(ModelProvider.OPENAI));
        verify(jdbcTemplate).queryForObject(anyString(), eq(String.class), eq(PROFILE));
    }

    @Test
    void resolvesEachNodeBindingToItsOrderedProviderAndModel() throws Exception {
        ProfileModelBindingService service = service(registry(
                registration(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                registration(ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT)));
        JsonNode snapshot = snapshot("""
                {
                  "analyze":{"primary":"llm-ops-analyze","fallback":["llm-ops-review"]},
                  "review":{"primary":"llm-ops-review","fallback":[]}
                }
                """);

        assertThat(service.resolve(
                snapshot, PROFILE, "analyze", "coding.analyze", ModelUseCase.CHAT))
                .extracting(ProviderModelRegistration::provider,
                        ProviderModelRegistration::modelId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                        org.assertj.core.groups.Tuple.tuple(
                                ModelProvider.GOOGLE_GENAI,
                                Stage2ProviderModels.GOOGLE_GENAI_CHAT));
        assertThat(service.resolve(
                snapshot, PROFILE, "review", "coding.review", ModelUseCase.TOOL_CALL))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.provider()).isEqualTo(ModelProvider.GOOGLE_GENAI);
                    assertThat(model.modelId())
                            .isEqualTo(Stage2ProviderModels.GOOGLE_GENAI_CHAT);
                });
        assertThat(service.resolve(
                snapshot, PROFILE, "analyze", "coding.analyze",
                ModelUseCase.STRUCTURED_OUTPUT))
                .extracting(ProviderModelRegistration::provider,
                        ProviderModelRegistration::modelId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                        org.assertj.core.groups.Tuple.tuple(
                                ModelProvider.GOOGLE_GENAI,
                                Stage2ProviderModels.GOOGLE_GENAI_CHAT));
    }

    @Test
    void rejectsMissingWrongNodeAndUnsupportedBindingDeterministically() throws Exception {
        ProfileModelBindingService service = service(registry(
                registration(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                registration(ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT)));
        JsonNode unknown = snapshot("""
                {
                  "analyze":{"primary":"unregistered-binding","fallback":[]},
                  "review":{"primary":"llm-ops-review","fallback":[]}
                }
                """);

        for (ThrowingRunnable call : List.<ThrowingRunnable>of(
                () -> service.resolve(
                        unknown, PROFILE, "analyze", "coding.analyze", ModelUseCase.CHAT),
                () -> service.resolve(
                        unknown, PROFILE, "missing", "coding.analyze", ModelUseCase.CHAT),
                () -> service.resolve(
                        unknown, PROFILE, "analyze", "coding.review", ModelUseCase.CHAT),
                () -> service.resolve(
                        unknown, UUID.randomUUID(), "analyze", "coding.analyze",
                        ModelUseCase.CHAT))) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                        assertThat(failure.code())
                                .isEqualTo(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
                        assertThat(failure.getMessage()).doesNotContain("unregistered-binding");
                    });
        }
    }

    @Test
    void rejectsABoundModelThatCannotSatisfyTheNodeUseCase() throws Exception {
        ProviderModelRegistration chatOnly = new ProviderModelRegistration(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                2);
        ProfileModelBindingService service = service(registry(chatOnly));
        JsonNode snapshot = snapshot("""
                {
                  "analyze":{"primary":"llm-ops-analyze","fallback":[]},
                  "review":{"primary":"llm-ops-analyze","fallback":[]}
                }
                """);

        assertThatThrownBy(() -> service.resolve(
                snapshot, PROFILE, "review", "coding.review", ModelUseCase.TOOL_CALL))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED));
    }

    @Test
    void theSameNodeIdUsesTheProviderAndModelFromItsFrozenProfileVersion() throws Exception {
        ProfileModelBindingService service = service(registry(
                registration(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                registration(ModelProvider.GOOGLE_GENAI,
                        Stage2ProviderModels.GOOGLE_GENAI_CHAT)));
        JsonNode first = snapshot(PROFILE, """
                {
                  "analyze":{"primary":"llm-ops-analyze","fallback":[]},
                  "review":{"primary":"llm-ops-review","fallback":[]}
                }
                """);
        JsonNode next = snapshot(NEXT_PROFILE, """
                {
                  "analyze":{"primary":"llm-ops-review","fallback":[]},
                  "review":{"primary":"llm-ops-review","fallback":[]}
                }
                """);

        assertThat(service.resolve(
                first, PROFILE, "analyze", "coding.analyze",
                ModelUseCase.STRUCTURED_OUTPUT))
                .extracting(ProviderModelRegistration::provider,
                        ProviderModelRegistration::modelId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT));
        assertThat(service.resolve(
                next, NEXT_PROFILE, "analyze", "coding.analyze",
                ModelUseCase.STRUCTURED_OUTPUT))
                .extracting(ProviderModelRegistration::provider,
                        ProviderModelRegistration::modelId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        ModelProvider.GOOGLE_GENAI,
                        Stage2ProviderModels.GOOGLE_GENAI_CHAT));
    }

    private ProfileModelBindingService service(ProviderCapabilityRegistry registry) {
        return new ProfileModelBindingService(
                mock(JdbcTemplate.class), objectMapper, registry);
    }

    private JsonNode snapshot(String modelBindings) throws Exception {
        return snapshot(PROFILE, modelBindings);
    }

    private JsonNode snapshot(UUID profileVersionId, String modelBindings) throws Exception {
        return objectMapper.readTree("""
                {
                  "profileVersionId":"%s",
                  "profileKey":"LLM_OPS",
                  "nodes":[
                    {"id":"analyze","type":"agent","handlerKey":"coding.analyze"},
                    {"id":"review","type":"agent","handlerKey":"coding.review"}
                  ],
                  "modelBindings":%s
                }
                """.formatted(profileVersionId, modelBindings));
    }

    private static ProviderCapabilityRegistry registry(
            ProviderModelRegistration... registrations) {
        return new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                ProviderCapabilityPolicy.stage2Baseline(),
                List.of(registrations));
    }

    private static ProviderModelRegistration registration(
            ModelProvider provider, String modelId) {
        return new ProviderModelRegistration(
                provider,
                modelId,
                Set.of(
                        ModelCapability.CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT),
                Duration.ofSeconds(30),
                2);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
