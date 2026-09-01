package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderFinishReasonTest {

    @Test
    void normalizesProviderCompletionReasons() {
        assertThat(ProviderFinishReason.normalize(ModelProvider.OPENAI, "stop", false))
                .isEqualTo(ProviderFinishReason.COMPLETED);
        assertThat(ProviderFinishReason.normalize(ModelProvider.GOOGLE_GENAI, "STOP", false))
                .isEqualTo(ProviderFinishReason.COMPLETED);
        assertThat(ProviderFinishReason.normalize(ModelProvider.ANTHROPIC, "end_turn", false))
                .isEqualTo(ProviderFinishReason.COMPLETED);
    }

    @Test
    void normalizesEachProviderTokenLimitWithoutMarkingItComplete() {
        assertThat(ProviderFinishReason.normalize(ModelProvider.OPENAI, "length", false))
                .isEqualTo(ProviderFinishReason.LENGTH_LIMIT);
        assertThat(ProviderFinishReason.normalize(ModelProvider.GOOGLE_GENAI, "MAX_TOKENS", false))
                .isEqualTo(ProviderFinishReason.LENGTH_LIMIT);
        assertThat(ProviderFinishReason.normalize(ModelProvider.ANTHROPIC, "max_tokens", false))
                .isEqualTo(ProviderFinishReason.LENGTH_LIMIT);
        assertThat(ProviderFinishReason.LENGTH_LIMIT.completed()).isFalse();
    }

    @Test
    void keepsFilteredAndUnknownResponsesIncomplete() {
        assertThat(ProviderFinishReason.normalize(
                ModelProvider.OPENAI, "content_filter", false))
                .isEqualTo(ProviderFinishReason.CONTENT_FILTERED);
        assertThat(ProviderFinishReason.normalize(
                ModelProvider.GOOGLE_GENAI, "SAFETY", false))
                .isEqualTo(ProviderFinishReason.CONTENT_FILTERED);
        assertThat(ProviderFinishReason.normalize(
                ModelProvider.ANTHROPIC, "pause_turn", false))
                .isEqualTo(ProviderFinishReason.INCOMPLETE);
        assertThat(ProviderFinishReason.normalize(ModelProvider.OPENAI, null, false))
                .isEqualTo(ProviderFinishReason.INCOMPLETE);
    }

    @Test
    void onlyNormalizesToolUseAsCompleteWhenCallsWereReturned() {
        assertThat(ProviderFinishReason.normalize(
                ModelProvider.ANTHROPIC, "tool_use", true))
                .isEqualTo(ProviderFinishReason.TOOL_CALLS);
        assertThat(ProviderFinishReason.normalize(
                ModelProvider.ANTHROPIC, "tool_use", false))
                .isEqualTo(ProviderFinishReason.INCOMPLETE);
    }
}
