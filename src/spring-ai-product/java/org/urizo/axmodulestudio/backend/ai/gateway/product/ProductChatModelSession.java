package org.urizo.axmodulestudio.backend.ai.gateway.product;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;

final class ProductChatModelSession implements AutoCloseable {

    private final ChatModel chatModel;
    private final Runnable closeAction;
    private boolean closed;

    ProductChatModelSession(ChatModel chatModel, Runnable closeAction) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel is required");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction is required");
    }

    ChatModel chatModel() {
        if (closed) {
            throw new IllegalStateException("Product chat model session is closed.");
        }
        return chatModel;
    }

    @Override
    public void close() {
        if (!closed) {
            closeAction.run();
            closed = true;
        }
    }
}
