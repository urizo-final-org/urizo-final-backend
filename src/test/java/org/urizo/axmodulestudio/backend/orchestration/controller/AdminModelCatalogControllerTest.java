package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.InferenceSettings;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.InferenceSupport;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalProviderSecretService;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderCredentialState;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderCredentialStatus;

class AdminModelCatalogControllerTest {

    @Test
    void exposesAllRegisteredModelsWithCredentialStateAndNoSecretMetadata() throws Exception {
        ProviderModelRegistration google = registration(
                ModelProvider.GOOGLE_GENAI, "gemini-test",
                new InferenceSupport(InferenceSettings.none(),
                        Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                InferenceSettings.ReasoningIntensity.HIGH), null));
        ProviderModelRegistration anthropic = registration(
                ModelProvider.ANTHROPIC, "claude-test",
                new InferenceSupport(InferenceSettings.none(),
                        Set.of(InferenceSettings.ReasoningIntensity.NONE,
                                InferenceSettings.ReasoningIntensity.HIGH),
                        new InferenceSupport.BudgetRange(1_024, 8_192, 1_024)));
        LocalProviderSecretService credentials = org.mockito.Mockito.mock(
                LocalProviderSecretService.class);
        when(credentials.statuses()).thenReturn(List.of(
                status(ModelProvider.GOOGLE_GENAI, ProviderCredentialState.VERIFIED),
                new ProviderCredentialStatus(ModelProvider.ANTHROPIC, false, null, null, null, null)));
        AdminModelCatalogController controller = new AdminModelCatalogController(
                new ProviderCapabilityRegistry(ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(), List.of(google, anthropic)),
                credentials);

        AdminModelCatalogController.CatalogView result = controller.list("LLM_OPS");

        assertThat(result.models()).hasSize(2);
        assertThat(result.models()).filteredOn(model -> model.provider().equals("GOOGLE_GENAI")).singleElement().satisfies(model -> {
            assertThat(model.selectionId()).isEqualTo("google-genai-gemini-test");
            assertThat(model.credentialState()).isEqualTo("VERIFIED");
            assertThat(model.inference().reasoningIntensity()).containsExactly("HIGH");
            assertThat(model.inference().reasoningBudgetTokens()).isNull();
        });
        assertThat(result.models()).filteredOn(model -> model.provider().equals("ANTHROPIC")).singleElement()
                .satisfies(model -> assertThat(model.credentialState()).isEqualTo("NOT_CONFIGURED"));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("fingerprint", "test-fingerprint", "updatedAt", "lastTestedAt");
        org.mockito.Mockito.verify(credentials).statuses();
        org.mockito.Mockito.verifyNoMoreInteractions(credentials);
    }

    @ParameterizedTest
    @EnumSource(ProviderCredentialState.class)
    void credentialProblemsDoNotRemoveModelsFromTheCatalog(ProviderCredentialState state) {
        LocalProviderSecretService credentials = org.mockito.Mockito.mock(LocalProviderSecretService.class);
        when(credentials.statuses()).thenReturn(List.of(status(ModelProvider.GOOGLE_GENAI, state)));
        ProviderModelRegistration google = registration(ModelProvider.GOOGLE_GENAI, "gemini-test",
                new InferenceSupport(InferenceSettings.none(), Set.of(InferenceSettings.ReasoningIntensity.NONE), null));
        AdminModelCatalogController controller = new AdminModelCatalogController(
                new ProviderCapabilityRegistry(ProviderLane.PRODUCT, ProviderCapabilityPolicy.stage2Baseline(), List.of(google)), credentials);
        assertThat(controller.list("LLM_OPS").models()).singleElement().satisfies(model -> {
            assertThat(model.model()).isEqualTo("gemini-test");
            assertThat(model.credentialState()).isEqualTo(state.name());
        });
    }

    private static ProviderCredentialStatus status(ModelProvider provider, ProviderCredentialState state) {
        return new ProviderCredentialStatus(provider, true, state, "test-fingerprint", Instant.EPOCH, Instant.EPOCH);
    }

    private static ProviderModelRegistration registration(
            ModelProvider provider, String modelId, InferenceSupport support) {
        return new ProviderModelRegistration(provider, modelId,
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT),
                Duration.ofSeconds(30), 2,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS,
                InferenceSettings.none(), support);
    }
}
