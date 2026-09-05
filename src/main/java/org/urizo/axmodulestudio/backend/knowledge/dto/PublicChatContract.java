package org.urizo.axmodulestudio.backend.knowledge.dto;

import java.net.URI;
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
     *
     * <p>category는 포털 탭 필터(F6)다. category_id 접두 목록을 받는다 — 확정된 탭 8종 중
     * 둘(체험·레저 = LS + EX, 관광지 잔여 = NA + HS + VE)이 접두 여러 개라 단일 값으로는
     * 표현되지 않는다. 생략·null·빈 목록은 "전체" 탭이며 필터를 걸지 않는다.
     * contenttypeid는 받지 않는다(함정 23).
     */
    public record PublicChatQueryRequest(
            @NotBlank @Size(max = 4000) String query,
            UUID conversationId,
            @Size(max = 8) List<@Size(max = 40) String> category) {
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
     * F10(2026-09-04 승인) 범위 — sourceUrl과 categoryLabel만 통과시킨다.
     * queryId · knowledgeVersionId · documentId · score는 계속 차단한다
     * (score 차단은 F2 미표시 확정과 정합).
     *
     * <p>categoryLabel은 {@code source_document.category}의 라벨 부분이다. 저장 형식이
     * {@code "category_id,category_label"}이라 첫 콤마 기준으로 나눈다. 접두 ID는 싣지
     * 않는다 — 탭이 이미 그 값으로 필터를 걸고 있다.
     *
     * <p>sourceUrl은 스킴이 https이나 현재 코퍼스 값은 로더가 만든 합성 주소
     * ({@code https://api-test.local/documents/{id}})라 브라우저에서 열리지 않는다.
     * 실제 원문 주소를 채우는 것은 수집 단계의 일이며 이 계약의 범위가 아니다.
     */
    public record PublicCitation(
            String title, String excerpt, URI sourceUrl, String categoryLabel) {
    }
}
