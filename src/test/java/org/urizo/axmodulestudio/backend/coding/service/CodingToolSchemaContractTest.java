package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.integration.DeploymentAdapter;
import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderToolDefinition;

/**
 * The digest in {@link CodingToolService#CODING_TOOL_SCHEMA_DIGESTS} is a constant, but it
 * is not free: the provider gateway recomputes it from the canonical schema and refuses the
 * whole call when the two disagree. A stale constant therefore does not degrade anything -
 * it kills the first model turn after a deploy, at a point where the failure reads as a
 * gateway fault rather than as a typo. Editing a tool's inputSchema without editing its
 * digest is exactly the mistake this test exists to catch, on the build rather than in a Job.
 */
class CodingToolSchemaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CodingHandlerStageService stageService() {
        return new CodingHandlerStageService(
                mock(CodingHandlerResultService.class),
                mock(CodingToolService.class),
                mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class),
                mock(CodingRunnerService.class),
                mock(DeploymentAdapter.class),
                mock(org.urizo.axmodulestudio.backend.orchestration.service
                        .ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                MAPPER,
                Clock.systemUTC());
    }

    @Test
    @DisplayName("Every declared Coding tool survives the gateway's own schema and digest check")
    void everyToolSchemaMatchesItsDigest() {
        List<JsonNode> schemas = stageService()
                .toolSchemas(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());

        assertThat(schemas)
                .hasSameSizeAs(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
        for (JsonNode schema : schemas) {
            // fromContract runs validateAndCanonicalize and matchesDigest - the same two
            // checks that stand between a model turn and a provider call in production.
            assertThatCode(() -> ProviderToolDefinition.fromContract(schema))
                    .as("tool %s", schema.path("name").asText())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("apply_patch offers both the diff form and the replacement form")
    void applyPatchDeclaresBothForms() {
        JsonNode schema = stageService().toolSchemas(java.util.Set.of("apply_patch")).get(0);
        JsonNode properties = schema.path("inputSchema").path("properties");

        List<String> declared = new java.util.ArrayList<>();
        properties.fieldNames().forEachRemaining(declared::add);
        assertThat(declared).containsExactlyInAnyOrder("patch", "path", "oldText", "newText");
        // Neither form can be required, because either one alone is a complete call.
        // CodingToolService is what refuses a mixture, in a sentence the model can act on.
        assertThat(schema.path("inputSchema").path("required")).isEmpty();
        assertThat(schema.path("description").asText())
                .contains("path, oldText and newText")
                .contains("appearing exactly once");
    }
}
