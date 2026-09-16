package org.urizo.axmodulestudio.backend.integration.ai.observability;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

/** Inclusive input counts and their cached subset; absent evidence never becomes zero. */
public record ProviderTokenUsage(Integer input, Integer output, Integer cachedInput) {
    public static ProviderTokenUsage from(String provider, Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) return new ProviderTokenUsage(null, null, null);
        Integer input = nonNegative(usage.getPromptTokens());
        Integer output = nonNegative(usage.getCompletionTokens());
        Integer cached = null;
        if ("openai".equalsIgnoreCase(provider) && usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage) {
            // DefaultUsage may fill missing native counters with zero; do not invent them.
            input = nonNegative(nativeUsage.promptTokens());
            output = nonNegative(nativeUsage.completionTokens());
            cached = nativeUsage.promptTokensDetails() == null ? null
                    : nonNegative(nativeUsage.promptTokensDetails().cachedTokens());
            if (input == null || (cached != null && cached > input)) cached = null;
        }
        return new ProviderTokenUsage(input, output, cached);
    }

    public Integer uncachedInput() { return cachedInput == null ? null : input - cachedInput; }
    private static Integer nonNegative(Integer value) { return value == null || value < 0 ? null : value; }
}
