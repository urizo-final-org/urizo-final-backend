package org.urizo.axmodulestudio.backend.knowledge.service;

import java.util.UUID;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

public interface RagOperations {

    ProductApiContract.ChatbotResponse createChatbot(
            UUID projectId,
            UUID traceId,
            String key,
            ProductApiContract.CreateChatbotRequest request);

    ProductApiContract.ChatbotListResponse listChatbots(UUID projectId, UUID traceId);

    ProductApiContract.ChatbotResponse getChatbot(UUID id, UUID traceId);

    ProductApiContract.RagQueryResponse query(
            UUID chatbotId,
            UUID traceId,
            String key,
            ProductApiContract.RagQueryRequest request);
}
