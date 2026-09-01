package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Locale;
import java.util.Objects;

public enum ProviderFinishReason {
    COMPLETED(true),
    TOOL_CALLS(true),
    LENGTH_LIMIT(false),
    CONTENT_FILTERED(false),
    INCOMPLETE(false);

    private final boolean completed;

    ProviderFinishReason(boolean completed) {
        this.completed = completed;
    }

    public boolean completed() {
        return completed;
    }

    public static ProviderFinishReason normalize(
            ModelProvider provider, String providerReason, boolean hasToolCalls) {
        Objects.requireNonNull(provider, "provider is required");
        String reason = providerReason == null
                ? ""
                : providerReason.trim().toLowerCase(Locale.ROOT).replace('-', '_');

        if (isLengthLimit(provider, reason)) {
            return LENGTH_LIMIT;
        }
        if (isContentFiltered(provider, reason)) {
            return CONTENT_FILTERED;
        }
        if (isIncomplete(provider, reason)) {
            return INCOMPLETE;
        }
        if (hasToolCalls) {
            return TOOL_CALLS;
        }
        if (isToolCall(provider, reason)) {
            return INCOMPLETE;
        }
        return isCompleted(provider, reason) ? COMPLETED : INCOMPLETE;
    }

    private static boolean isCompleted(ModelProvider provider, String reason) {
        return switch (provider) {
            case OPENAI -> "stop".equals(reason);
            case GOOGLE_GENAI -> "stop".equals(reason);
            case ANTHROPIC -> "end_turn".equals(reason) || "stop_sequence".equals(reason);
            case VERTEX_AI_GEMINI -> false;
        };
    }

    private static boolean isToolCall(ModelProvider provider, String reason) {
        return switch (provider) {
            case OPENAI -> "tool_calls".equals(reason) || "function_call".equals(reason);
            case GOOGLE_GENAI -> "tool_calls".equals(reason);
            case ANTHROPIC -> "tool_use".equals(reason);
            case VERTEX_AI_GEMINI -> false;
        };
    }

    private static boolean isLengthLimit(ModelProvider provider, String reason) {
        return switch (provider) {
            case OPENAI -> "length".equals(reason);
            case GOOGLE_GENAI -> "max_tokens".equals(reason);
            case ANTHROPIC -> "max_tokens".equals(reason);
            case VERTEX_AI_GEMINI -> false;
        };
    }

    private static boolean isContentFiltered(ModelProvider provider, String reason) {
        return switch (provider) {
            case OPENAI -> "content_filter".equals(reason);
            case GOOGLE_GENAI -> switch (reason) {
                case "safety", "recitation", "blocklist", "prohibited_content",
                        "spii", "image_safety" -> true;
                default -> false;
            };
            case ANTHROPIC -> "refusal".equals(reason);
            case VERTEX_AI_GEMINI -> false;
        };
    }

    private static boolean isIncomplete(ModelProvider provider, String reason) {
        return switch (provider) {
            case OPENAI -> false;
            case GOOGLE_GENAI -> switch (reason) {
                case "other", "malformed_function_call", "unexpected_tool_call",
                        "too_many_tool_calls", "missing_thought_signature" -> true;
                default -> false;
            };
            case ANTHROPIC -> "pause_turn".equals(reason);
            case VERTEX_AI_GEMINI -> true;
        };
    }
}
