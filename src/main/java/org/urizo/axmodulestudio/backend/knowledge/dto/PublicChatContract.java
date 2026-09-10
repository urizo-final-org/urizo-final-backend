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
     *
     * <p>previousQuery는 같은 대화의 직전 사용자 질문이다. 서버가 대화 이력을 보관하지
     * 않으므로 호출자가 들고 온다 — 저장이 없으니 만료·정리·세션 격리 문제도 없다.
     * 검색 임베딩에만 얹고 근거 필터·문장 추출에는 쓰지 않는다({@code RagStore.searchText}).
     * 이력 전체가 아니라 직전 한 건만 받는다: 대명사를 푸는 데 필요한 것은 직전 턴이고,
     * 목록으로 열어 두면 익명 호출자가 임베딩 입력 길이를 마음대로 늘릴 수 있다.
     */
    public record PublicChatQueryRequest(
            @NotBlank @Size(max = 4000) String query,
            UUID conversationId,
            @Size(max = 8) List<@Size(max = 40) String> category,
            @Size(max = 4000) String previousQuery) {
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
     *
     * <p>eventStatus는 행사 종료일({@code source_document.event_end_date})이 서버 기준
     * 오늘보다 과거면 "ENDED", 그 외에는 null이다. 값은 이 둘뿐이다.
     *
     * <p>imageUrl은 {@code source_document.image_url}이며 사진이 없는 문서는 null이다
     * (표본 코퍼스 500건 중 405건만 보유). sourceUrl과 달리 원천이 준 실제 주소라 브라우저에서
     * 열린다 — 대신 http와 https가 섞여 있어 https 페이지에서는 혼합 콘텐츠로 차단될 수 있다.
     * 스킴을 https로 바꿔 적지 않는다: 원천에 없는 주소를 지어내는 것이 되고, 틀린 사진은
     * 사진이 없는 것보다 나쁘다.
     */
    public record PublicCitation(
            String title, String excerpt, URI sourceUrl, String categoryLabel,
            String eventStatus, String imageUrl) {
    }
}
