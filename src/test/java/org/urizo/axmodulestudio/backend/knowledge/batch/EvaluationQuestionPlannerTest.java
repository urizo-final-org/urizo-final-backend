package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.knowledge.batch.EvaluationQuestionPlanner.CandidateDocument;
import org.urizo.axmodulestudio.backend.knowledge.batch.EvaluationQuestionPlanner.GeneratedQuestion;
import org.urizo.axmodulestudio.backend.knowledge.config.EvaluationLlmProperties;

/**
 * 출제 검증 규칙(AXMS-AI02-020). 유출된 문항은 시험을 쉽게 만들어 지표를 부풀린다 —
 * 여기서 걸러지지 않으면 "품질이 좋아 보이는" 세트가 조용히 확정된다.
 */
class EvaluationQuestionPlannerTest {

    private static final CandidateDocument DOCUMENT = new CandidateDocument(
            "PBLN_000000000000001",
            "[부산] 남구 제2회 청년 창업 아이디어 경진대회 참가자 모집 공고",
            "창업을 준비하는 관내 예비창업자를 대상으로 사업화 교육과 멘토링을 지원한다.\n"
                    + "[신청기간] 20260901 ~ 20260930\n"
                    + "[소관기관] 중소벤처기업부\n"
                    + "[수행기관] 부산창조경제혁신센터",
            "창업",
            "digest-1");

    @Test
    void acceptsARealisticUserQuestion() {
        assertThat(EvaluationQuestionPlanner.usable(
                "창업한 지 얼마 안 됐는데 교육이나 도움을 받을 수 있는 데가 있나요?", DOCUMENT)).isTrue();
    }

    @Test
    void rejectsAQuestionThatCopiesTheTitle() {
        assertThat(EvaluationQuestionPlanner.usable(
                "청년 창업 아이디어 경진대회 참가자 모집에 지원하려면 어떻게 하나요?", DOCUMENT)).isFalse();
    }

    @Test
    void rejectsAQuestionThatCopiesTheBody() {
        assertThat(EvaluationQuestionPlanner.usable(
                "관내 예비창업자를 대상으로 사업화 교육과 멘토링을 지원하는 사업이 있나요?", DOCUMENT)).isFalse();
    }

    @Test
    void rejectsAQuestionNamingTheInstitution() {
        assertThat(EvaluationQuestionPlanner.usable(
                "중소벤처기업부에서 받을 수 있는 창업 지원이 궁금합니다", DOCUMENT)).isFalse();
    }

    @Test
    void rejectsLengthOutOfRange() {
        assertThat(EvaluationQuestionPlanner.usable("창업 지원?", DOCUMENT)).isFalse();
        assertThat(EvaluationQuestionPlanner.usable(
                "가".repeat(EvaluationQuestionPlanner.MAX_QUESTION + 1), DOCUMENT)).isFalse();
    }

    @Test
    void institutionValuesComeFromTheLabelledLines() {
        assertThat(EvaluationQuestionPlanner.institutionValues(DOCUMENT.content()))
                .containsExactly("중소벤처기업부", "부산창조경제혁신센터");
    }

    @Test
    void generateKeepsOnlyValidQuestionsAndMapsIndexesBackToDocuments() {
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        CandidateDocument second = new CandidateDocument(
                "PBLN_000000000000002", "서울 강남구 소상공인 경영안정자금 융자 지원 공고",
                "관내 소상공인의 안정적인 사업 운영을 돕는다.", null, "digest-2");
        when(gateway.chat(any())).thenReturn(response("""
                {"questions":[
                  {"documentIndex":1,"question":"창업 초기인데 멘토링 받을 만한 곳이 있을까요?"},
                  {"documentIndex":1,"question":"중복 번호는 무시되어야 하는 두 번째 질문입니다"},
                  {"documentIndex":2,"question":"소상공인 경영안정자금 융자 지원은 어떻게 신청하나요?"},
                  {"documentIndex":99,"question":"존재하지 않는 번호를 가리키는 질문입니다"}
                ]}"""));

        List<GeneratedQuestion> questions = planner(gateway, true)
                .generate(List.of(DOCUMENT, second));

        // 유효 1건만 남는다: 중복 번호는 첫 문항 우선, 2번은 자기 제목을 베껴 탈락, 99번은 범위 밖.
        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).source().externalDocumentId())
                .isEqualTo("PBLN_000000000000001");
    }

    @Test
    void disabledPlannerNeverCallsTheGateway() {
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);

        assertThat(planner(gateway, false).generate(List.of(DOCUMENT))).isEmpty();
        verifyNoInteractions(gateway);
    }

    private static EvaluationQuestionPlanner planner(
            ProviderChatGatewayPort gateway, boolean enabled) {
        return new EvaluationQuestionPlanner(
                gateway,
                Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper(),
                new EvaluationLlmProperties(
                        enabled, Duration.ofSeconds(5), ModelProvider.ANTHROPIC, null));
    }

    private static ProviderChatResponse response(String content) {
        return new ProviderChatResponse(
                ModelProvider.ANTHROPIC, "claude-haiku-4-5-20251001", content,
                10, 5, Duration.ofMillis(100));
    }
}
