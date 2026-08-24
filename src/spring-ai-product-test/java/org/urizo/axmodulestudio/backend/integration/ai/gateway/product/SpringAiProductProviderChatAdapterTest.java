package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.TransientAiException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatAdapterRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGateway;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

class SpringAiProductProviderChatAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-11T07:30:00Z");
    private static final String FIXTURE_CREDENTIAL = "fixture-only-not-a-real-key";

    @Test
    void bindsOpenAiThroughTheCmsResolverAndSpringAiChatModelContract() {
        verifiesMockContract(ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT);
    }

    @Test
    void bindsGoogleGenAiThroughTheCmsResolverAndSpringAiChatModelContract() {
        verifiesMockContract(ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT);
    }

    @Test
    void constructsConcreteProductLaneClientsWithoutMakingRemoteCalls() {
        try (ProductChatModelSession openAi = new OpenAiProductChatModelFactory()
                        .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.OPENAI_CHAT);
                ProductChatModelSession google = new GoogleGenAiProductChatModelFactory()
                        .open(FIXTURE_CREDENTIAL, Stage2ProviderModels.GOOGLE_GENAI_CHAT)) {
            assertThat(openAi.chatModel()).isInstanceOf(OpenAiChatModel.class);
            assertThat(google.chatModel()).isInstanceOf(GoogleGenAiChatModel.class);
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
        when(factory.open(FIXTURE_CREDENTIAL, Stage2ProviderModels.OPENAI_CHAT))
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

    private static void verifiesMockContract(ModelProvider provider, String modelId) {
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        ProductChatModelFactory factory = mock(ProductChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        ProviderCredentialLease lease = ProviderCredentialLease.fromBytes(
                provider,
                FIXTURE_CREDENTIAL.getBytes(StandardCharsets.US_ASCII));
        when(resolver.resolve(provider)).thenReturn(lease);
        when(factory.provider()).thenReturn(provider);
        when(factory.open(FIXTURE_CREDENTIAL, modelId))
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
                "Reply with exactly OK.",
                NOW.plusSeconds(30));

        ProviderChatResponse result = adapter.chat(registration, request);

        assertThat(result.content()).isEqualTo("OK");
        assertThat(result.inputTokens()).isEqualTo(6);
        assertThat(result.outputTokens()).isEqualTo(1);
        assertThat(result.latency()).isZero();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).isEqualTo("Reply with exactly OK.");
        assertThatThrownBy(lease::copySecret).isInstanceOf(IllegalStateException.class);
    }

    private static ChatResponse response() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("OK"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(6, 1))
                        .build());
    }
}
