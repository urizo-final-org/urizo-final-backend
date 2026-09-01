package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;

class NaturalCmsModelBoundaryTest {

    @Test
    void cmsToolSchemasAreAcceptedOnlyByTheNaturalCmsEntryPoint() {
        Instant now = Instant.parse("2026-08-30T08:00:00Z");
        ObjectMapper mapper = new ObjectMapper();
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        ProviderModelRegistration registration = new ProviderModelRegistration(
                ModelProvider.OPENAI,
                "cms-test-model",
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                1);
        CodingModelTurnService service = new CodingModelTurnService(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                gateway,
                mapper,
                Clock.fixed(now, ZoneOffset.UTC),
                false);
        ObjectNode input = mapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        input.putObject("properties");
        input.putArray("required");
        ObjectNode schema = mapper.createObjectNode()
                .put("name", "resolve_cms_target")
                .put("description", "Resolve the Spring-supplied CMS target.")
                .put("schemaDigest", NaturalCmsToolContract.MODEL_TOOL_SCHEMA_DIGESTS
                        .get("resolve_cms_target"));
        schema.set("inputSchema", input);
        CodingModelTurnContract.Request request = new CodingModelTurnContract.Request(
                "1.0",
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "natural-cms.boundary-test",
                1,
                1,
                "cms_preview",
                "natural-cms-v1",
                "sha256:" + "a".repeat(64),
                List.of("CHAT", "TOOL_CALLING"),
                List.of(mapper.createObjectNode()
                        .put("role", "user").put("content", "Resolve it.")),
                List.of(schema),
                mapper.createObjectNode().put("type", "TEXT"),
                now.plusSeconds(30));

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(ProviderGatewayException.class)
                .satisfies(failure -> assertThat(
                        ((ProviderGatewayException) failure).code())
                        .isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED));

        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.OPENAI,
                "cms-test-model",
                "resolved",
                1,
                1,
                Duration.ofMillis(1)));

        assertThat(service.executeNaturalCms(request).assistant().content())
                .isEqualTo("resolved");
    }
}
