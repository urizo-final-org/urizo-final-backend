package org.urizo.axmodulestudio.backend.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.dto.PublicChatContract;

/**
 * 공개 응답에 관리자 전용 값이 새지 않는지 지키는 회귀 테스트.
 *
 * <p>필드가 늘어나는 쪽으로 실수하기 쉬우므로 매핑 결과만이 아니라 record 구성 자체를 본다.
 */
class PublicChatResponseBoundaryTest {

    /** F10(2026-09-04 승인)으로 sourceUrl은 공개 범위에 들어왔다. 나머지 넷은 계속 차단한다. */
    private static final Set<String> ADMINISTRATOR_ONLY = Set.of(
            "queryId", "knowledgeVersionId", "documentId", "score");

    private static final URI SOURCE_URL =
            URI.create("https://fixture.invalid/documents/local-tourism-001");

    private static final UUID TRACE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID QUERY_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID CONVERSATION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID KNOWLEDGE_VERSION_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

    @Test
    void publicRecordsDeclareNoAdministratorOnlyFields() {
        assertThat(componentNames(PublicChatContract.PublicChatResponse.class))
                .doesNotContainAnyElementsOf(ADMINISTRATOR_ONLY);
        assertThat(componentNames(PublicChatContract.PublicCitation.class))
                .doesNotContainAnyElementsOf(ADMINISTRATOR_ONLY);
    }

    @Test
    void publicCitationCarriesExactlyTheApprovedFourFields() {
        assertThat(componentNames(PublicChatContract.PublicCitation.class))
                .containsExactly("title", "excerpt", "sourceUrl", "categoryLabel");
    }

    @Test
    void categoryIsOptionalOnThePublicRequest() {
        assertThat(componentNames(PublicChatContract.PublicChatQueryRequest.class))
                .containsExactly("query", "conversationId", "category");
        assertThat(new PublicChatContract.PublicChatQueryRequest("한옥스테이", null, null)
                .category()).isNull();
    }

    @Test
    void administratorResponseIsNarrowedToThePublicFields() {
        PublicChatContract.PublicChatResponse response = PublicChatController.toPublic(
                new ProductApiContract.RagQueryResponse(
                        ProductApiContract.SCHEMA_VERSION, TRACE_ID, QUERY_ID, CONVERSATION_ID,
                        "ANSWERED", "활성 지식의 근거에 따르면 속초 해수욕장은 …",
                        List.of(new ProductApiContract.Citation(
                                "local-tourism-001", "속초 해수욕장", SOURCE_URL,
                                "[분류] 관광지\n속초 해수욕장은 …", 0.8123, "관광지 > 해수욕장")),
                        KNOWLEDGE_VERSION_ID, Instant.parse("2026-08-31T12:00:00Z")));

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.outcome()).isEqualTo("ANSWERED");
        assertThat(response.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.title()).isEqualTo("속초 해수욕장");
            assertThat(citation.excerpt()).startsWith("[분류] 관광지");
            assertThat(citation.sourceUrl()).isEqualTo(SOURCE_URL);
            assertThat(citation.sourceUrl().getScheme()).isEqualTo("https");
            assertThat(citation.categoryLabel()).isEqualTo("관광지 > 해수욕장");
        });
    }

    @Test
    void refusedAnswerKeepsAnEmptyCitationList() {
        PublicChatContract.PublicChatResponse response = PublicChatController.toPublic(
                new ProductApiContract.RagQueryResponse(
                        ProductApiContract.SCHEMA_VERSION, TRACE_ID, QUERY_ID, CONVERSATION_ID,
                        "REFUSED", "활성 지식에서 답변을 뒷받침할 근거를 찾지 못했습니다.",
                        List.of(), KNOWLEDGE_VERSION_ID, Instant.parse("2026-08-31T12:00:00Z")));

        assertThat(response.outcome()).isEqualTo("REFUSED");
        assertThat(response.citations()).isEmpty();
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
