package org.urizo.axmodulestudio.backend.knowledge.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.dto.PublicChatContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;
import org.urizo.axmodulestudio.backend.knowledge.security.PublicChatRateLimiter;
import org.urizo.axmodulestudio.backend.knowledge.service.RagOperations;

/**
 * 관리자 세션 없이 호출하는 공개 질의 엔드포인트.
 *
 * <p>기존 관리자 API의 인증을 제거하지 않는다. 공개 대상 chatbot 하나만 설정으로
 * 지정하고, 경로에 chatbot 식별자를 두지 않아 익명 호출자가 내부 UUID를 알 수 없다.
 *
 * <p>{@code knowledge.controller} 패키지에 두어 기존 {@code ProductApiExceptionHandler}의
 * 오류 정규화(429 → RATE_LIMITED, 409 → KNOWLEDGE_VERSION_NOT_ACTIVE)를 그대로 받는다.
 */
@RestController
@Validated
@Profile("local-full")
@RequestMapping("/api/public")
public class PublicChatController {

    private final RagOperations rag;
    private final PublicChatRateLimiter rateLimiter;
    private final UUID publicChatbotId;

    PublicChatController(
            RagOperations rag,
            PublicChatRateLimiter rateLimiter,
            @Value("${ax.knowledge.public-chatbot-id:}") String publicChatbotId) {
        this.rag = rag;
        this.rateLimiter = rateLimiter;
        // 잘못된 값이면 기동 시점에 실패시킨다. 공개 경로 설정 오류를 런타임까지 끌고 가지 않는다.
        this.publicChatbotId = publicChatbotId == null || publicChatbotId.isBlank()
                ? null
                : UUID.fromString(publicChatbotId.trim());
    }

    @PostMapping("/chat/query")
    PublicChatContract.PublicChatResponse query(
            @Valid @RequestBody PublicChatContract.PublicChatQueryRequest body,
            HttpServletRequest request) {
        rateLimiter.check(request);
        if (publicChatbotId == null) {
            throw new ProductApiException(
                    "PUBLIC_CHATBOT_NOT_CONFIGURED",
                    "No public chatbot is configured.",
                    HttpStatus.NOT_FOUND);
        }
        // 활성 Knowledge Version 강제는 RagStore.query가 이미 한다(active_version_id만 조회).
        // 공개 경로용 추가 검증을 넣지 않는다 — 검증이 두 곳에 있으면 한 곳이 뒤처진다.
        return toPublic(rag.publicQuery(
                publicChatbotId,
                trace(request),
                new ProductApiContract.RagQueryRequest(
                        ProductApiContract.SCHEMA_VERSION,
                        body.query(),
                        body.conversationId(),
                        null)));
    }

    /** 관리자 전용 값(queryId, knowledgeVersionId, documentId, sourceUrl, score)을 떨어뜨린다. */
    static PublicChatContract.PublicChatResponse toPublic(
            ProductApiContract.RagQueryResponse answer) {
        return new PublicChatContract.PublicChatResponse(
                answer.schemaVersion(),
                answer.traceId(),
                answer.conversationId(),
                answer.outcome(),
                answer.answer(),
                answer.citations().stream()
                        .map(citation -> new PublicChatContract.PublicCitation(
                                citation.title(), citation.excerpt()))
                        .toList(),
                answer.generatedAt());
    }

    private static UUID trace(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}
