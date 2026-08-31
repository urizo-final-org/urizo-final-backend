package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatAdapter;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFailure;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFailureKind;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;

@Component
@Profile("dev & !coding-model-turn-local-mock")
final class SpringAiProductProviderChatAdapter implements ProviderChatAdapter {

    private final ProviderCredentialResolver credentialResolver;
    private final Map<ModelProvider, ProductChatModelFactory> factories;
    private final Clock clock;

    SpringAiProductProviderChatAdapter(
            ProviderCredentialResolver credentialResolver,
            List<ProductChatModelFactory> factories,
            Clock clock) {
        this.credentialResolver = credentialResolver;
        this.clock = clock;
        Map<ModelProvider, ProductChatModelFactory> indexed = new EnumMap<>(ModelProvider.class);
        for (ProductChatModelFactory factory : factories) {
            if (indexed.putIfAbsent(factory.provider(), factory) != null) {
                throw new IllegalArgumentException("Duplicate Product Lane chat model factory.");
            }
        }
        this.factories = Map.copyOf(indexed);
    }

    @Override
    public Set<ModelProvider> providers() {
        return factories.keySet();
    }

    @Override
    public ProviderChatResponse chat(
            ProviderModelRegistration registration,
            ProviderChatRequest request) {
        if (registration.provider() != request.provider()
                || !registration.modelId().equals(request.modelId())) {
            throw new IllegalArgumentException("Provider chat registration does not match the request.");
        }
        ProductChatModelFactory factory = factories.get(request.provider());
        if (factory == null) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }

        Instant startedAt = clock.instant();
        try (ProviderCredentialLease lease = credentialResolver.resolve(request.provider())) {
            byte[] credentialBytes = lease.copySecret();
            try {
                String credential = new String(credentialBytes, StandardCharsets.US_ASCII);
                try (ProductChatModelSession session = factory.open(
                        credential,
                        registration.modelId(),
                        registration.maxOutputTokens())) {
                    ChatResponse response = session.chatModel().call(
                            new Prompt(request.messages().stream()
                                    .map(SpringAiProductProviderChatAdapter::springMessage)
                                    .toList()));
                    return response(request, response, startedAt);
                }
            }
            finally {
                Arrays.fill(credentialBytes, (byte) 0);
            }
        }
    }

    private static Message springMessage(ProviderChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> AssistantMessage.builder()
                    .content(message.content())
                    .toolCalls(message.toolCalls().stream()
                            .map(call -> new AssistantMessage.ToolCall(
                                    call.id(), "function", call.name(), call.arguments()))
                            .toList())
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId(), message.toolName(), message.content())))
                    .build();
        };
    }

    private ProviderChatResponse response(
            ProviderChatRequest request,
            ChatResponse response,
            Instant startedAt) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int inputTokens = usage == null ? 0 : nonNegative(usage.getPromptTokens());
        int outputTokens = usage == null ? 0 : nonNegative(usage.getCompletionTokens());
        Duration latency = Duration.between(startedAt, clock.instant());
        return new ProviderChatResponse(
                request.provider(),
                request.modelId(),
                content,
                inputTokens,
                outputTokens,
                latency.isNegative() ? Duration.ZERO : latency);
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }
}
