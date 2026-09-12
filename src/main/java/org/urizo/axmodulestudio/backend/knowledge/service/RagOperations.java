package org.urizo.axmodulestudio.backend.knowledge.service;

import java.util.List;
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

    /**
     * 공개 경로용 질의. 멱등 Key를 요구하지 않는다({@code ProductService} 구현 주석 참조).
     * {@code projectId}는 답변 프롬프트의 도메인 선택에 쓴다 — null이면 기본(관광) 경로다.
     */
    ProductApiContract.RagQueryResponse publicQuery(
            UUID chatbotId,
            UUID traceId,
            ProductApiContract.RagQueryRequest request,
            List<String> category,
            String previousQuery,
            UUID projectId);
}
