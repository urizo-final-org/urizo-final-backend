package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.InferenceSettings;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.InferenceSupport;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalProviderSecretService;

class AdminModelCatalogControllerTest {

    @Test
    void exposesRegisteredInferenceMetadataOnlyForVerifiedCredentials() {
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
        when(credentials.hasVerifiedCredential(ModelProvider.GOOGLE_GENAI)).thenReturn(true);
        when(credentials.hasVerifiedCredential(ModelProvider.ANTHROPIC)).thenReturn(false);
        AdminModelCatalogController controller = new AdminModelCatalogController(
                new ProviderCapabilityRegistry(ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(), List.of(google, anthropic)),
                credentials);

        AdminModelCatalogController.CatalogView result = controller.list("LLM_OPS");

        assertThat(result.models()).singleElement().satisfies(model -> {
            assertThat(model.selectionId()).isEqualTo("google-genai-gemini-test");
            assertThat(model.inference().reasoningIntensity()).containsExactly("HIGH");
            assertThat(model.inference().reasoningBudgetTokens()).isNull();
        });
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
