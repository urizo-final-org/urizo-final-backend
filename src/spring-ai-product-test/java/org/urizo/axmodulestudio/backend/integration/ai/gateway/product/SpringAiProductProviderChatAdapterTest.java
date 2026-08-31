package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatAdapterRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGateway;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderToolDefinition;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

class SpringAiProductProviderChatAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-11T07:30:00Z");
    private static final String FIXTURE_CREDENTIAL = "fixture-only-not-a-real-key";
    private static final int OUTPUT_BUDGET = 12_345;

    @Test
    void bindsOpenAiThroughTheCmsResolverAndSpringAiChatModelContract() {
        verifiesMockContract(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT);
    }

    @Test
    void bindsGoogleGenAiThroughTheCmsResolverAndSpringAiChatModelContract() {
        verifiesMockContract(ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT);
    }

    @Test
    void bindsAnthropicThroughTheCmsResolverAndSpringAiChatModelContract() {
        verifiesMockContract(ModelProvider.ANTHROPIC, Stage2ProviderModels.ANTHROPIC_CHAT);
    }

    @Test
    void mapsOpenAiNativeFunctionDefinitionsAndNormalizesItsToolCall() throws Exception {
        verifiesNativeToolContract(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT);
    }

    @Test
    void mapsGeminiNativeFunctionDefinitionsAndNormalizesItsToolCall() throws Exception {
        verifiesNativeToolContract(
                ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT);
    }

    @Test
    void mapsOpenAiJsonSchemaIntoTheNativeProviderRequest() throws Exception {
        verifiesNativeStructuredOutputContract(
                ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT);
    }

    @Test
    void mapsGeminiJsonSchemaIntoTheNativeProviderRequest() throws Exception {
        verifiesNativeStructuredOutputContract(
                ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT);
    }

    @Test
    void mapsAnthropicJsonSchemaIntoTheNativeProviderRequest() throws Exception {
        verifiesNativeStructuredOutputContract(
                ModelProvider.ANTHROPIC, Stage2ProviderModels.ANTHROPIC_CHAT);
    }

    @Test
    void restoresGeminiThoughtSignaturesOnlyInsideTheNativeFollowUpPrompt() throws Exception {
        byte[] signature = { 1, 2, 3, 4 };
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(resolver.resolve(ModelProvider.GOOGLE_GENAI)).thenAnswer(ignored ->
                ProviderCredentialLease.fromBytes(
                        ModelProvider.GOOGLE_GENAI,
                        FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII)));
        when(factory.provider()).thenReturn(ModelProvider.GOOGLE_GENAI);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                Stage2ProviderModels.GOOGLE_GENAI_CHAT,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenAnswer(ignored -> new ProductChatModelSession(chatModel, () -> { }));
        AssistantMessage nativeCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "provider-id", "function", "read_file",
                        "{\"path\":\"README.md\"}")))
                .properties(Map.of("thoughtSignatures", List.of(signature)))
                .build();
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(nativeCall))))
                .thenReturn(response());
        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver, List.of(factory), Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderModelRegistration registration = toolRegistration(
                ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT);
        ProviderChatRequest firstRequest = nativeRequest(
                ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT);

        ProviderChatResponse first = adapter.chat(registration, firstRequest);
        ProviderChatMessage.ToolCall call = first.toolCalls().get(0);
        signature[0] = 9;
        ProviderChatRequest followUp = new ProviderChatRequest(
                ModelProvider.GOOGLE_GENAI,
                Stage2ProviderModels.GOOGLE_GENAI_CHAT,
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER, "Read the approved file."),
                        ProviderChatMessage.assistant("", first.toolCalls()),
                        ProviderChatMessage.tool(
                                call.id(), call.name(), "{\"content\":\"fixture\"}")),
                firstRequest.tools(),
                NOW.plusSeconds(30));
        ProviderChatResponse second = adapter.chat(registration, followUp);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.times(2)).call(prompts.capture());
        AssistantMessage restored = (AssistantMessage) prompts.getAllValues().get(1)
                .getInstructions().get(1);
        assertThat(restored.getMetadata().get("thoughtSignatures"))
                .isInstanceOfSatisfying(List.class, values -> {
                    assertThat(values).hasSize(1);
                    assertThat((byte[]) values.get(0)).containsExactly(1, 2, 3, 4);
                });
        GoogleGenAiChatModel.GeminiRequest nativeFollowUp =
                createGeminiProviderRequest(prompts.getAllValues().get(1));
        assertThat(nativeFollowUp.contents().get(1).parts().orElseThrow().get(0)
                .thoughtSignature().orElseThrow()).containsExactly(1, 2, 3, 4);
        assertThat(first.toString()).doesNotContain("thoughtSignatures");
        assertThat(second.content()).isEqualTo("OK");
    }

    @Test
    void constructsConcreteProductLaneClientsWithoutMakingRemoteCalls() {
        try (ProductChatModelSession openAi = new OpenAiProductChatModelFactory()
                        .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.OPENAI_CHAT, OUTPUT_BUDGET);
                ProductChatModelSession google = new GoogleGenAiProductChatModelFactory()
                        .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.GOOGLE_GENAI_CHAT, OUTPUT_BUDGET);
                ProductChatModelSession anthropic = new AnthropicProductChatModelFactory()
                        .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.ANTHROPIC_CHAT, OUTPUT_BUDGET)) {
            assertThat(openAi.chatModel()).isInstanceOf(OpenAiChatModel.class);
            assertThat(google.chatModel()).isInstanceOf(GoogleGenAiChatModel.class);
            assertThat(anthropic.chatModel()).isInstanceOf(AnthropicChatModel.class);
            assertThat(((org.springframework.ai.openai.OpenAiChatOptions)
                    openAi.chatModel().getDefaultOptions()).getMaxCompletionTokens())
                    .isEqualTo(OUTPUT_BUDGET);
            assertThat(((org.springframework.ai.google.genai.GoogleGenAiChatOptions)
                    google.chatModel().getDefaultOptions()).getMaxOutputTokens())
                    .isEqualTo(OUTPUT_BUDGET);
            assertThat(((AnthropicChatOptions)
                    anthropic.chatModel().getDefaultOptions()).getMaxTokens())
                    .isEqualTo(OUTPUT_BUDGET);
        }
    }

    @Test
    void normalizesSpringAiProviderFailuresWithoutLeakingRawMessages() {
        String rawValue = "raw-provider-or-secret-value-must-not-leak";
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(resolver.resolve(ModelProvider.OPENAI)).thenAnswer(ignored -> ProviderCredentialLease.fromBytes(
                ModelProvider.OPENAI,
                FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII)));
        when(factory.provider()).thenReturn(ModelProvider.OPENAI);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                Stage2ProviderModels.OPENAI_CHAT,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenAnswer(ignored -> new ProductChatModelSession(chatModel, () -> { }));
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenThrow(new TransientAiException(rawValue));

        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver,
                List.of(factory),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderModelRegistration registration = new ProviderModelRegistration(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                1);
        ProviderChatGateway gateway = new ProviderChatGateway(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                new ProviderChatAdapterRegistry(List.of(adapter)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                "Reply with exactly OK.",
                NOW.plusSeconds(30));

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ModelGatewayErrorCode.INTERNAL_TRANSIENT_ERROR);
                    assertThat(failure.getMessage()).isEqualTo("A transient model gateway failure occurred.");
                    assertThat(failure.toString()).doesNotContain(rawValue);
                });
    }

    @Test
    void rejectsUnknownNativeCallsThroughTheSafeGatewayEnvelope() {
        String rawProviderId = "raw-provider-call-id-must-not-leak";
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(resolver.resolve(ModelProvider.OPENAI)).thenAnswer(ignored ->
                ProviderCredentialLease.fromBytes(
                        ModelProvider.OPENAI,
                        FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII)));
        when(factory.provider()).thenReturn(ModelProvider.OPENAI);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                Stage2ProviderModels.OPENAI_CHAT,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenAnswer(ignored -> new ProductChatModelSession(chatModel, () -> { }));
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(nativeResponse(
                        rawProviderId, "shell_exec", "{\"command\":\"secret\"}"));

        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver, List.of(factory), Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderModelRegistration registration = toolRegistration(
                ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT);
        ProviderChatGateway gateway = new ProviderChatGateway(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                new ProviderChatAdapterRegistry(List.of(adapter)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> gateway.chat(nativeRequest(
                ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT)))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(
                            ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
                    assertThat(failure.getMessage())
                            .isEqualTo("Model provider response failed validation.");
                    assertThat(failure.toString())
                            .doesNotContain(rawProviderId)
                            .doesNotContain("secret");
                });
    }

    @Test
    void bridgesTheExistingStrictEnvelopeWithoutSendingItsSchemaPrompt() throws Exception {
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(resolver.resolve(ModelProvider.OPENAI)).thenAnswer(ignored ->
                ProviderCredentialLease.fromBytes(
                        ModelProvider.OPENAI,
                        FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII)));
        when(factory.provider()).thenReturn(ModelProvider.OPENAI);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                Stage2ProviderModels.OPENAI_CHAT,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenAnswer(ignored -> new ProductChatModelSession(chatModel, () -> { }));
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(nativeResponse(
                        "provider-id", "read_file", "{\"path\":\"README.md\"}"));
        ObjectNode contract = toolContract();
        String declared = ProviderToolDefinition.LEGACY_TOOL_PROMPT_PREFIX
                + new ObjectMapper().writeValueAsString(
                        JsonNodeFactory.instance.arrayNode().add(contract));
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.OPENAI,
                Stage2ProviderModels.OPENAI_CHAT,
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.SYSTEM, declared),
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER, "Read the approved file.")),
                NOW.plusSeconds(30));
        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver, List.of(factory), Clock.fixed(NOW, ZoneOffset.UTC));

        ProviderChatResponse response = adapter.chat(
                toolRegistration(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                request);

        assertThat(response.content()).isEqualTo(
                "{\"assistant\":\"\",\"toolCalls\":[{\"name\":\"read_file\","
                        + "\"arguments\":{\"path\":\"README.md\"}}]}");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).singleElement()
                .satisfies(message -> assertThat(message.getText())
                        .isEqualTo("Read the approved file."));
    }

    private static void verifiesMockContract(ModelProvider provider, String modelId) {
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        ProviderCredentialLease lease = ProviderCredentialLease.fromBytes(
                provider,
                FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII));
        when(resolver.resolve(provider)).thenReturn(lease);
        when(factory.provider()).thenReturn(provider);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                modelId,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenReturn(new ProductChatModelSession(chatModel, () -> { }));
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(response());

        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver,
                List.of(factory),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderModelRegistration registration = new ProviderModelRegistration(
                provider,
                modelId,
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                1);
        ProviderChatRequest request = new ProviderChatRequest(
                provider,
                modelId,
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.SYSTEM,
                                "Stay inside the approved scope."),
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER,
                                "Read the approved fixture."),
                        ProviderChatMessage.assistant(
                                "",
                                List.of(new ProviderChatMessage.ToolCall(
                                        "77777777-7777-4777-8777-777777777777",
                                        "read_file",
                                        "{\"path\":\"README.md\"}"))),
                        ProviderChatMessage.tool(
                                "77777777-7777-4777-8777-777777777777",
                                "read_file",
                                "{\"content\":\"fixture\"}")),
                NOW.plusSeconds(30));

        ProviderChatResponse result = adapter.chat(registration, request);

        assertThat(result.content()).isEqualTo("OK");
        assertThat(result.inputTokens()).isEqualTo(6);
        assertThat(result.outputTokens()).isEqualTo(1);
        assertThat(result.latency()).isZero();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .satisfiesExactly(
                        message -> {
                            assertThat(message).isInstanceOf(SystemMessage.class);
                            assertThat(message.getText()).isEqualTo("Stay inside the approved scope.");
                        },
                        message -> {
                            assertThat(message).isInstanceOf(UserMessage.class);
                            assertThat(message.getText()).isEqualTo("Read the approved fixture.");
                        },
                        message -> {
                            assertThat(message).isInstanceOf(AssistantMessage.class);
                            assertThat(((AssistantMessage) message).getToolCalls())
                                    .singleElement()
                                    .satisfies(call -> {
                                        assertThat(call.id()).isEqualTo(
                                                "77777777-7777-4777-8777-777777777777");
                                        assertThat(call.name()).isEqualTo("read_file");
                                    });
                        },
                        message -> {
                            assertThat(message).isInstanceOf(ToolResponseMessage.class);
                            assertThat(((ToolResponseMessage) message).getResponses())
                                    .singleElement()
                                    .satisfies(tool -> {
                                        assertThat(tool.id()).isEqualTo(
                                                "77777777-7777-4777-8777-777777777777");
                                        assertThat(tool.name()).isEqualTo("read_file");
                                        assertThat(tool.responseData())
                                                .isEqualTo("{\"content\":\"fixture\"}");
                                    });
                        });
        verify(factory).open(
                FIXTURE_CREDENTIAL,
                modelId,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS);
        assertThatThrownBy(lease::copySecret).isInstanceOf(IllegalStateException.class);
    }

    private static void verifiesNativeToolContract(ModelProvider provider, String modelId)
            throws Exception {
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(resolver.resolve(provider)).thenAnswer(ignored -> ProviderCredentialLease.fromBytes(
                provider, FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII)));
        when(factory.provider()).thenReturn(provider);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                modelId,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenAnswer(ignored -> new ProductChatModelSession(chatModel, () -> { }));
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(nativeResponse(
                        "provider-specific-id", "read_file",
                        "{\"path\":\"README.md\"}"));
        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver, List.of(factory), Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderChatRequest request = nativeRequest(provider, modelId);
        ProviderModelRegistration registration = toolRegistration(provider, modelId);

        ProviderChatResponse first = adapter.chat(registration, request);
        ProviderChatResponse second = adapter.chat(registration, request);

        assertThat(first.content()).isEmpty();
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(UUID.fromString(call.id())).isNotNull();
            assertThat(call.id()).isNotEqualTo("provider-specific-id");
            assertThat(call.name()).isEqualTo("read_file");
            assertThat(call.arguments()).isEqualTo("{\"path\":\"README.md\"}");
            assertThat(call.id()).isEqualTo(second.toolCalls().get(0).id());
        });
        assertThat(first.toString()).doesNotContain("provider-specific-id");

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.times(2)).call(prompts.capture());
        Prompt prompt = prompts.getAllValues().get(0);
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("read_file");
            assertThat(callback.getToolDefinition().description())
                    .isEqualTo("Read one approved file.");
            assertThat(callback.getToolDefinition().inputSchema())
                    .isEqualTo("{\"additionalProperties\":false,\"properties\":"
                            + "{\"path\":{\"type\":\"string\"}},"
                            + "\"required\":[\"path\"],\"type\":\"object\"}");
        });
        if (provider == ModelProvider.OPENAI) {
            assertOpenAiProviderRequest(prompt);
        }
        else {
            assertGeminiProviderRequest(prompt);
        }
    }

    private static void verifiesNativeStructuredOutputContract(
            ModelProvider provider, String modelId) throws Exception {
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(resolver.resolve(provider)).thenAnswer(ignored -> ProviderCredentialLease.fromBytes(
                provider, FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII)));
        when(factory.provider()).thenReturn(provider);
        when(factory.open(
                FIXTURE_CREDENTIAL,
                modelId,
                ProviderModelRegistration.DEFAULT_MAX_OUTPUT_TOKENS))
                .thenAnswer(ignored -> new ProductChatModelSession(chatModel, () -> { }));
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("{\"port\":\"feasible\",\"payload\":{}}")))));
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("port").add("payload");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("port").put("type", "string");
        properties.putObject("payload").put("type", "object");
        ProviderResponseFormat format = ProviderResponseFormat.jsonSchema(schema);
        ProviderChatRequest request = new ProviderChatRequest(
                provider,
                modelId,
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.SYSTEM, "Stay in scope."),
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER, "Analyze the request.")),
                List.of(),
                format,
                NOW.plusSeconds(30));
        ProviderModelRegistration registration = new ProviderModelRegistration(
                provider,
                modelId,
                Set.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT),
                Duration.ofSeconds(30),
                1);
        SpringAiProductProviderChatAdapter adapter = new SpringAiProductProviderChatAdapter(
                resolver, List.of(factory), Clock.fixed(NOW, ZoneOffset.UTC));

        adapter.chat(registration, request);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getMessageType().name())
                .containsExactly("SYSTEM", "USER");
        assertThat(prompt.getValue().getOptions())
                .isInstanceOf(StructuredOutputChatOptions.class);

        if (provider == ModelProvider.OPENAI) {
            try (ProductChatModelSession session = new OpenAiProductChatModelFactory()
                    .open(FIXTURE_CREDENTIAL, modelId, OUTPUT_BUDGET)) {
                Method createRequest = OpenAiChatModel.class.getDeclaredMethod(
                        "createRequest", Prompt.class, boolean.class);
                createRequest.setAccessible(true);
                OpenAiApi.ChatCompletionRequest nativeRequest =
                        (OpenAiApi.ChatCompletionRequest) createRequest.invoke(
                                session.chatModel(), prompt.getValue(), false);
                assertThat(nativeRequest.responseFormat().getType().name())
                        .isEqualTo("JSON_SCHEMA");
                JsonNode nativeSchema = new ObjectMapper().valueToTree(
                        nativeRequest.responseFormat().getJsonSchema().getSchema());
                assertThat(nativeSchema)
                        .isEqualTo(format.outputSchema());
                assertThat(nativeRequest.responseFormat().getJsonSchema().getStrict())
                        .isTrue();
            }
        }
        else if (provider == ModelProvider.GOOGLE_GENAI) {
            GoogleGenAiChatModel.GeminiRequest nativeRequest =
                    createGeminiProviderRequest(prompt.getValue());
            assertThat(nativeRequest.config().responseMimeType())
                    .contains("application/json");
            assertThat(nativeRequest.config().responseJsonSchema().orElseThrow())
                    .isInstanceOfSatisfying(com.google.genai.types.Schema.class,
                            nativeSchema -> {
                                assertThat(nativeSchema.type().orElseThrow().knownEnum().name())
                                        .isEqualTo("OBJECT");
                                assertThat(nativeSchema.properties().orElseThrow())
                                        .containsOnlyKeys("port", "payload");
                                assertThat(nativeSchema.required().orElseThrow())
                                        .containsExactly("port", "payload");
                            });
        }
        else {
            try (ProductChatModelSession session = new AnthropicProductChatModelFactory()
                    .open(FIXTURE_CREDENTIAL, modelId, OUTPUT_BUDGET)) {
                Method createRequest = AnthropicChatModel.class.getDeclaredMethod(
                        "createRequest", Prompt.class, boolean.class);
                createRequest.setAccessible(true);
                AnthropicApi.ChatCompletionRequest nativeRequest =
                        (AnthropicApi.ChatCompletionRequest) createRequest.invoke(
                                session.chatModel(), prompt.getValue(), false);
                assertThat(nativeRequest.outputFormat().schema())
                        .containsKeys("type", "properties", "required")
                        .containsEntry("additionalProperties", false);
            }
        }
    }

    private static void assertOpenAiProviderRequest(Prompt prompt) throws Exception {
        try (ProductChatModelSession session = new OpenAiProductChatModelFactory()
                .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.OPENAI_CHAT, OUTPUT_BUDGET)) {
            Method createRequest = OpenAiChatModel.class.getDeclaredMethod(
                    "createRequest", Prompt.class, boolean.class);
            createRequest.setAccessible(true);
            OpenAiApi.ChatCompletionRequest request =
                    (OpenAiApi.ChatCompletionRequest) createRequest.invoke(
                            session.chatModel(), prompt, false);

            assertThat(request.tools()).singleElement().satisfies(tool -> {
                assertThat(tool.getType().name()).isEqualTo("FUNCTION");
                assertThat(tool.getFunction().getName()).isEqualTo("read_file");
                assertThat(tool.getFunction().getDescription())
                        .isEqualTo("Read one approved file.");
                assertThat(tool.getFunction().getParameters())
                        .containsEntry("type", "object")
                        .containsKey("properties")
                        .containsEntry("additionalProperties", false);
            });
            assertThat(request.parallelToolCalls()).isFalse();
        }
    }

    private static void assertGeminiProviderRequest(Prompt prompt) throws Exception {
        GoogleGenAiChatModel.GeminiRequest request = createGeminiProviderRequest(prompt);
        com.google.genai.types.FunctionDeclaration declaration = request.config()
                .tools().orElseThrow().get(0)
                .functionDeclarations().orElseThrow().get(0);

        assertThat(declaration.name()).contains("read_file");
        assertThat(declaration.description()).contains("Read one approved file.");
        com.google.genai.types.Schema parameters = declaration.parameters().orElseThrow();
        assertThat(parameters.type().orElseThrow().toString()).isEqualTo("OBJECT");
        assertThat(parameters.required().orElseThrow()).containsExactly("path");
        assertThat(parameters.properties().orElseThrow().get("path")
                .type().orElseThrow().toString()).isEqualTo("STRING");
    }

    private static GoogleGenAiChatModel.GeminiRequest createGeminiProviderRequest(
            Prompt prompt) throws Exception {
        try (ProductChatModelSession session = new GoogleGenAiProductChatModelFactory()
                .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.GOOGLE_GENAI_CHAT, OUTPUT_BUDGET)) {
            Method createRequest = GoogleGenAiChatModel.class.getDeclaredMethod(
                    "createGeminiRequest", Prompt.class);
            createRequest.setAccessible(true);
            return (GoogleGenAiChatModel.GeminiRequest) createRequest.invoke(
                    session.chatModel(), prompt);
        }
    }

    private static ProviderChatRequest nativeRequest(ModelProvider provider, String modelId) {
        return new ProviderChatRequest(
                provider,
                modelId,
                List.of(ProviderChatMessage.plain(
                        ProviderChatMessage.Role.USER, "Read the approved file.")),
                List.of(new ProviderToolDefinition(
                        "read_file", "Read one approved file.", readFileSchema())),
                NOW.plusSeconds(30));
    }

    private static ProviderModelRegistration toolRegistration(
            ModelProvider provider, String modelId) {
        return new ProviderModelRegistration(
                provider,
                modelId,
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                1);
    }

    private static ChatResponse nativeResponse(
            String id, String name, String arguments) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, arguments)))
                .build();
        return new ChatResponse(
                List.of(new Generation(assistant)),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(8, 2))
                        .build());
    }

    private static ObjectNode toolContract() {
        ObjectNode input = readFileSchema();
        ProviderToolDefinition definition = new ProviderToolDefinition(
                "read_file", "Read one approved file.", input);
        ObjectNode contract = JsonNodeFactory.instance.objectNode();
        contract.put("name", definition.name());
        contract.put("description", definition.description());
        contract.set("inputSchema", definition.inputSchema());
        contract.put("schemaDigest", definition.schemaDigest());
        return contract;
    }

    private static ObjectNode readFileSchema() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("type", "object").put("additionalProperties", false);
        input.putArray("required").add("path");
        input.putObject("properties").putObject("path").put("type", "string");
        return input;
    }

    private static ChatResponse response() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("OK"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(6, 1))
                        .build());
    }
}
