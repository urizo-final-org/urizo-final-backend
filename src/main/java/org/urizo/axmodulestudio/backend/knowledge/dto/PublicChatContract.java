package org.urizo.axmodulestudio.backend.knowledge.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 익명 방문자용 공개 질의 계약.
 *
 * <p>{@link ProductApiContract.RagQueryResponse}를 그대로 쓰지 않는다. 관리자 전용 값
 * (knowledgeVersionId, score, queryId, documentId)과 해석 불가능한 합성 sourceUrl이
 * 공개 응답에 섞이지 않도록 필드를 좁힌 별도 계약이다. 관리자 계약에 필드가 늘어도
 * 이 계약은 따라가지 않는다 — 그게 이 파일이 존재하는 이유다.
 */
public final class PublicChatContract {

    private PublicChatContract() {
    }

    /**
     * schemaVersion과 topK를 받지 않는다. 공개 호출자가 검색 폭을 늘려 DB 작업량을
     * 키울 수 없고, 브라우저 클라이언트에 계약 버전을 요구하지도 않는다.
     */
    public record PublicChatQueryRequest(
            @NotBlank @Size(max = 4000) String query,
            UUID conversationId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicChatResponse(
            String schemaVersion,
            UUID traceId,
            UUID conversationId,
            String outcome,
            String answer,
            List<PublicCitation> citations,
            Instant generatedAt) {

        public PublicChatResponse {
            citations = List.copyOf(citations);
        }
    }

    /**
     * sourceUrl은 담지 않는다. 현재 값은 원본 홈페이지 URL이 아니라 해석되지 않는
     * 합성 URL이라(예: {@code https://fixture.invalid/documents/...}) 공개 화면에서
     * 죽은 링크가 된다. 원본 URL은 본문에 남아 excerpt로 전달된다.
     */
    public record PublicCitation(String title, String excerpt) {
    }
}
