package org.urizo.axmodulestudio.backend.orchestration.controller;

import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalProviderSecretService;

@RestController
@Profile("local-full")
@RequestMapping("/api/admin/ai/model-catalog")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class AdminModelCatalogController {
    private static final Set<String> PROFILE_KEYS = Set.of("LLM_OPS", "NATURAL_CMS");
    private final ProviderCapabilityRegistry registry;
    private final LocalProviderSecretService credentials;

    public AdminModelCatalogController(ProviderCapabilityRegistry registry,
            LocalProviderSecretService credentials) {
        this.registry = registry;
        this.credentials = credentials;
    }

    @GetMapping
    CatalogView list(@RequestParam String profileKey) {
        if (!PROFILE_KEYS.contains(profileKey)) throw new IllegalArgumentException("profileKey is unsupported");
        return new CatalogView("1.0", profileKey, registry.registrations().stream()
                .filter(model -> credentials.hasVerifiedCredential(model.provider()))
                .map(AdminModelCatalogController::view).toList());
    }

    private static ModelView view(ProviderModelRegistration model) {
        return new ModelView(selectionId(model), model.provider().name(), model.modelId(),
                model.capabilities().stream().map(Enum::name).sorted().toList(),
                new InferenceView(new SettingsView(
                        model.inferenceSettings().reasoningIntensity().name(),
                        model.inferenceSettings().reasoningBudgetTokens()),
                        model.inferenceSupport().reasoningIntensities().stream()
                                .map(Enum::name).sorted().toList(),
                        model.inferenceSupport().reasoningBudgetTokens() == null ? null
                                : new BudgetView(
                                        model.inferenceSupport().reasoningBudgetTokens().min(),
                                        model.inferenceSupport().reasoningBudgetTokens().max(),
                                        model.inferenceSupport().reasoningBudgetTokens().multipleOf())));
    }

    private static String selectionId(ProviderModelRegistration model) {
        return model.selectionId();
    }

    public record CatalogView(String schemaVersion, String profileKey, List<ModelView> models) { }
    public record ModelView(String selectionId, String provider, String model,
            List<String> capabilities, InferenceView inference) { }
    public record InferenceView(@JsonProperty("default") SettingsView defaultSettings,
            List<String> reasoningIntensity, BudgetView reasoningBudgetTokens) { }
    public record SettingsView(String reasoningIntensity, Integer reasoningBudgetTokens) { }
    public record BudgetView(int min, int max, int multipleOf) { }
}
