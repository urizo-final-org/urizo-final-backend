package org.urizo.axmodulestudio.backend.knowledge.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;
import org.urizo.axmodulestudio.backend.knowledge.service.RagOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** projectId → 공개 챗봇 해석 규칙: ACTIVE가 정확히 하나일 때만 답한다. */
class PublicChatProjectRoutingTest {

    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void resolvesTheSingleActiveChatbotOfTheProject() {
        UUID active = UUID.randomUUID();
        PublicChatController controller = controller(
                chatbot(active, "ACTIVE"), chatbot(UUID.randomUUID(), "ARCHIVED"));
        assertThat(controller.chatbotOf(PROJECT, UUID.randomUUID())).isEqualTo(active);
    }

    @Test
    void refusesWhenTheProjectHasNoActiveChatbot() {
        PublicChatController controller = controller(chatbot(UUID.randomUUID(), "ARCHIVED"));
        assertThatThrownBy(() -> controller.chatbotOf(PROJECT, UUID.randomUUID()))
                .isInstanceOf(ProductApiException.class)
                .hasMessageContaining("exactly one active chatbot");
    }

    @Test
    void refusesInsteadOfPickingWhenTwoChatbotsAreActive() {
        // 어느 도메인이 답했는지 알 수 없는 것이 조용히 틀린 답보다 나쁘다 — 아무거나 고르지 않는다.
        PublicChatController controller = controller(
                chatbot(UUID.randomUUID(), "ACTIVE"), chatbot(UUID.randomUUID(), "ACTIVE"));
        assertThatThrownBy(() -> controller.chatbotOf(PROJECT, UUID.randomUUID()))
                .isInstanceOf(ProductApiException.class)
                .hasMessageContaining("exactly one active chatbot");
    }

    private static PublicChatController controller(ProductApiContract.ChatbotResponse... items) {
        RagOperations rag = mock(RagOperations.class);
        when(rag.listChatbots(eq(PROJECT), any())).thenReturn(
                new ProductApiContract.ChatbotListResponse(
                        ProductApiContract.SCHEMA_VERSION, UUID.randomUUID(), List.of(items)));
        return new PublicChatController(rag, null, "");
    }

    private static ProductApiContract.ChatbotResponse chatbot(UUID chatbotId, String status) {
        return new ProductApiContract.ChatbotResponse(
                ProductApiContract.SCHEMA_VERSION, UUID.randomUUID(), chatbotId,
                PROJECT, UUID.randomUUID(), "공개 챗봇", status, Instant.now());
    }
}
