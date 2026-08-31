package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ProviderChatMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    private static final int MAX_CONTENT_CHARACTERS = 200_000;
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,119}$");

    public ProviderChatMessage {
        role = Objects.requireNonNull(role, "role is required");
        content = Objects.requireNonNull(content, "content is required");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls are required"));
        if (content.length() > MAX_CONTENT_CHARACTERS) {
            throw new IllegalArgumentException("message content is too long");
        }
        switch (role) {
            case SYSTEM, USER -> {
                if (content.isBlank() || !toolCalls.isEmpty()
                        || toolCallId != null || toolName != null) {
                    throw new IllegalArgumentException("system and user messages require plain content");
                }
            }
            case ASSISTANT -> {
                if ((content.isBlank() && toolCalls.isEmpty())
                        || toolCallId != null || toolName != null) {
                    throw new IllegalArgumentException("assistant message shape is invalid");
                }
            }
            case TOOL -> {
                if (!toolCalls.isEmpty() || !validUuid(toolCallId)
                        || toolName == null || !TOOL_NAME.matcher(toolName).matches()) {
                    throw new IllegalArgumentException("tool message shape is invalid");
                }
            }
        }
    }

    public static ProviderChatMessage plain(Role role, String content) {
        return new ProviderChatMessage(role, content, List.of(), null, null);
    }

    public static ProviderChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ProviderChatMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static ProviderChatMessage tool(
            String toolCallId, String toolName, String content) {
        return new ProviderChatMessage(
                Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    @Override
    public String toString() {
        return "ProviderChatMessage[role=" + role
                + ", content=REDACTED, toolCalls=" + toolCalls.size() + "]";
    }

    private static boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        }
        catch (RuntimeException failure) {
            return false;
        }
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public record ToolCall(String id, String name, String arguments) {
        public ToolCall {
            if (!validUuid(id)
                    || name == null || !TOOL_NAME.matcher(name).matches()
                    || arguments == null || arguments.isBlank()
                    || arguments.length() > MAX_CONTENT_CHARACTERS) {
                throw new IllegalArgumentException("assistant tool call is invalid");
            }
        }

        @Override
        public String toString() {
            return "ToolCall[id=" + id + ", name=" + name
                    + ", arguments=REDACTED]";
        }
    }
}
