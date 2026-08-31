package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProviderChatRequestTest {

    private static final Instant DEADLINE = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void keepsMessagesImmutableAndRedactsTheirContents() {
        var source = new java.util.ArrayList<>(List.of(
                ProviderChatMessage.plain(
                        ProviderChatMessage.Role.SYSTEM, "secret system fixture"),
                ProviderChatMessage.plain(
                        ProviderChatMessage.Role.USER, "secret user fixture")));
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.OPENAI, "fixture-model", source, DEADLINE);

        source.clear();

        assertThat(request.messages()).hasSize(2);
        assertThat(request.toString())
                .contains("messages=REDACTED")
                .doesNotContain("secret system fixture")
                .doesNotContain("secret user fixture");
        assertThat(request.messages().toString())
                .doesNotContain("secret system fixture")
                .doesNotContain("secret user fixture")
                .contains("content=REDACTED");
    }

    @Test
    void toolCallArgumentsCountTowardTheExistingInputBound() {
        String oversizedArguments = "{\"value\":\"" + "a".repeat(65_536) + "\"}";
        ProviderChatMessage assistant = ProviderChatMessage.assistant(
                "",
                List.of(new ProviderChatMessage.ToolCall(
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString(),
                        "read_file",
                        oversizedArguments)));

        assertThatThrownBy(() -> new ProviderChatRequest(
                ModelProvider.OPENAI,
                "fixture-model",
                List.of(assistant),
                DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages exceed the bounded chat request");
    }
}
