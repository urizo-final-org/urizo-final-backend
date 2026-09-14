package org.urizo.axmodulestudio.backend.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFinishReason;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.knowledge.config.PublicChatLlmProperties;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.dto.PublicChatContract;

/**
 * 이 클래스가 지키는 것은 하나다 — <b>LLM이 무엇을 하든 포털은 답을 돌려준다.</b>
 * 실패·타임아웃·빈 응답은 전부 추출식 답변으로 돌아가야 한다(R51).
 */
class PublicAnswerComposerTest {

    private static final UUID TRACE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String EXTRACTIVE = "진주남강유등축제는 남강 일원에서 열린다.";

    /** 다른 테스트가 재사용한다 — 꺼진 Composer는 응답을 그대로 통과시킨다. */
    static PublicAnswerComposer disabled() {
        return composer(false, Duration.ofSeconds(20), request -> {
            throw new AssertionError("Disabled composer must not call the provider.");
        });
    }

    @Test
    void flagOffKeepsTheExtractiveAnswerAndNeverCallsTheProvider() {
        ProductApiContract.RagQueryResponse grounded = answered();

        assertThat(disabled().rewrite("언제 열려?", grounded, null, PublicChatContract.AnswerStyle.DETAILED)).isSameAs(grounded);
    }

    @Test
    void flagOnReplacesOnlyTheAnswer() {
        PublicAnswerComposer composer = composer(
                true, Duration.ofSeconds(20), request -> reply("  다시 쓴 산문 답변.  "));
        ProductApiContract.RagQueryResponse grounded = answered();

        ProductApiContract.RagQueryResponse rewritten = composer.rewrite("언제 열려?", grounded, null, PublicChatContract.AnswerStyle.DETAILED);

        assertThat(rewritten.answer()).isEqualTo("다시 쓴 산문 답변.");
        assertThat(rewritten.outcome()).isEqualTo("ANSWERED");
        assertThat(rewritten.citations()).isEqualTo(grounded.citations());
        assertThat(rewritten.queryId()).isEqualTo(grounded.queryId());
        assertThat(rewritten.conversationId()).isEqualTo(grounded.conversationId());
        assertThat(rewritten.knowledgeVersionId()).isEqualTo(grounded.knowledgeVersionId());
        assertThat(rewritten.generatedAt()).isEqualTo(grounded.generatedAt());
    }

    @Test
    void providerFailureFallsBackToTheExtractiveAnswer() {
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_NOT_CONFIGURED, "no credential");
        });

        assertThat(composer.rewrite("언제 열려?", answered(), null, PublicChatContract.AnswerStyle.DETAILED).answer()).isEqualTo(EXTRACTIVE);
    }

    @Test
    void aCallThatOutlivesTheBudgetFallsBackToTheExtractiveAnswer() {
        PublicAnswerComposer composer = composer(true, Duration.ofMillis(80), request -> {
            try {
                Thread.sleep(5_000);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return reply("늦게 도착한 답변");
        });

        Instant startedAt = Instant.now();
        ProductApiContract.RagQueryResponse rewritten = composer.rewrite("언제 열려?", answered(), null, PublicChatContract.AnswerStyle.DETAILED);

        assertThat(rewritten.answer()).isEqualTo(EXTRACTIVE);
        // 벽시계 상한이 실제로 걸린다. 상한이 없으면 provider가 5초를 붙잡는다.
        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void blankProviderOutputFallsBackToTheExtractiveAnswer() {
        PublicAnswerComposer composer = composer(
                true, Duration.ofSeconds(20), request -> reply("   "));

        assertThat(composer.rewrite("언제 열려?", answered(), null, PublicChatContract.AnswerStyle.DETAILED).answer()).isEqualTo(EXTRACTIVE);
    }

    @Test
    void refusalNeverReachesTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            calls.incrementAndGet();
            return reply("근거 없이 지어낸 답변");
        });
        ProductApiContract.RagQueryResponse refused = new ProductApiContract.RagQueryResponse(
                "1.0", TRACE, ID, ID, "REFUSED",
                "활성 지식에서 답변을 뒷받침할 근거를 찾지 못했습니다.", List.of(), ID, Instant.EPOCH);

        assertThat(composer.rewrite("반도체 공정", refused, null, PublicChatContract.AnswerStyle.DETAILED)).isSameAs(refused);
        assertThat(calls).hasValue(0);
    }

    /**
     * 도메인 프롬프트 선택(axms-ai02-011). projectId 유무가 유일한 스위치다 — 관광은
     * projectId 없이 기본 챗봇 설정으로 들어오는 유일한 경로이고, 중기부는 시연에서
     * 라이브로 등록되어 UUID를 미리 알 수 없기 때문이다.
     */
    @Test
    void anExplicitProjectSpeaksTheGrantDomainPrompt() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });

        composer.rewrite("청년 창업 지원 사업 있어?", answered(), UUID.randomUUID(), PublicChatContract.AnswerStyle.DETAILED);

        String system = captured.get().messages().get(0).content();
        assertThat(system).isEqualTo(PublicAnswerComposer.SME_SYSTEM_PROMPT);
        assertThat(system)
                .contains("지원사업 안내 챗봇")
                // 관광과 반대다 — 이 도메인의 핵심 가치가 신청방법·문의처 인용이다.
                .contains("신청방법과 문의처가 근거에 있으면 반드시 포함한다")
                // 자격 판정은 지어내지 않는다(시연 시나리오 3).
                .contains("선정될 수 있는지는 판정하지")
                .doesNotContain("[상태]");
    }

    /**
     * 챗봇은 근거 카드를 빠짐없이 풀어 설명한다(AXMS-AI02-025). 길이 상한을 다시 넣으면
     * 셋 중 둘만 설명하고 하나를 건너뛰는 답변이 돌아온다 — 카드에는 있는데 답변에는 없는
     * 상태가 사람 눈에는 "빠뜨렸다"로 읽힌다.
     */
    @Test
    void theDetailedPromptDemandsEveryCitationAndSetsNoLengthCap() {
        for (String prompt : List.of(
                PublicAnswerComposer.SYSTEM_PROMPT, PublicAnswerComposer.SME_SYSTEM_PROMPT)) {
            assertThat(prompt)
                    .contains("하나도 빠뜨리지 않는다")
                    .contains("길어져도 된다")
                    .doesNotContain("500자");
        }
    }

    /**
     * 통합검색 요약은 결과 목록 바로 위에 놓인다 — 카드마다 길게 풀면 같은 말을 두 번 한다.
     * 덮어쓴다는 사실을 프롬프트가 명시해야 어느 규칙을 따를지 흔들리지 않는다.
     */
    @Test
    void theBriefStyleOverridesTheChunkFormatOnBothDomains() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });

        composer.rewrite("축제", answered(), null, PublicChatContract.AnswerStyle.BRIEF);
        String tourism = captured.get().messages().get(0).content();
        composer.rewrite("창업 지원", answered(), UUID.randomUUID(), PublicChatContract.AnswerStyle.BRIEF);
        String grant = captured.get().messages().get(0).content();

        for (String prompt : List.of(tourism, grant)) {
            assertThat(prompt)
                    // 도메인 규칙은 그대로 남는다 — 근거 밖 내용 금지가 풀리면 안 된다.
                    .contains("제공된 근거 문서의 내용만 사용한다")
                    .contains("결과를 하나씩 설명하지 않는다")
                    .contains("두세 문장");
        }
        assertThat(tourism).startsWith(PublicAnswerComposer.SYSTEM_PROMPT);
        assertThat(grant).startsWith(PublicAnswerComposer.SME_SYSTEM_PROMPT);
    }

    /** 생략·DETAILED는 덧붙임 없이 도메인 프롬프트 그대로다. */
    @Test
    void theDetailedStyleAddsNothing() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });

        composer.rewrite("축제", answered(), null, PublicChatContract.AnswerStyle.DETAILED);

        assertThat(captured.get().messages().get(0).content())
                .isEqualTo(PublicAnswerComposer.SYSTEM_PROMPT);
    }

    @Test
    void promptCarriesTheGroundingAndTheFaithfulnessConstraints() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });

        composer.rewrite("진주남강유등축제 언제 열려?", answered(), null, PublicChatContract.AnswerStyle.DETAILED);

        ProviderChatRequest request = captured.get();
        assertThat(request.provider()).isEqualTo(ModelProvider.ANTHROPIC);
        assertThat(request.modelId()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(request.tools()).isEmpty();

        String system = request.messages().get(0).content();
        assertThat(system).isEqualTo(PublicAnswerComposer.SYSTEM_PROMPT);
        assertThat(system)
                .contains("제공된 근거 문서의 내용만 사용한다")
                .contains("근거에 없는 정보를 추가하지 않는다")
                .contains("부족하면, 지어내지 말고 부족하다고 말한다")
                // 규칙 4. 산문 상한이 아니라 덩어리 구조 지시로 바뀌었다(axms-ai02-014).
                // 길이 상한은 2026-09-14에 없앴다 — 근거 카드를 빠짐없이 설명하려면
                // 답변이 길어질 수 있어야 한다(AXMS-AI02-025).
                .contains("한 곳씩 덩어리로 나눈다")
                // 규칙 5. 종료 문구를 모델 판단이 아니라 [상태] 줄에 고정한다(axms-ai02-014).
                .contains("[상태] 줄이 있으면 그 행사가 종료됐다는 사실을 답변에 반드시")
                .contains("[상태] 줄이 없으면 종료 여부를 판단하거나 언급하지 않는다")
                // 규칙 6. 톤과 그 예외 — 사실을 추측형으로 흐리지 못하게 막는 문장이 함께 있어야
                // 규칙 5가 톤 요구에 밀리지 않는다.
                .contains("다정하고 상냥한 존댓말")
                .contains("이모지는 쓰지")   // 줄바꿈이 끼므로 문장 전체로 단언하지 않는다
                .contains("추측형으로 흐리지 않는다")
                // 규칙 7. 화면이 마크다운을 렌더링하지 않으므로 기호를 쓰면 글자로 노출된다.
                .contains("마크다운 기호를 쓰지 않는다")
                .contains("가운뎃점");

        String user = request.messages().get(1).content();
        assertThat(user)
                .contains("진주남강유등축제")
                .contains("[행사기간] 20261112 ~ 20261127")
                .contains("진주남강유등축제 언제 열려?");
    }

    @Test
    void groundingIsCappedAtTheExcerptLimit() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });
        String oversized = "가".repeat(900);

        composer.rewrite("질문", answered(citation("제목", oversized)), null, PublicChatContract.AnswerStyle.DETAILED);

        assertThat(captured.get().messages().get(1).content()).contains("가".repeat(500));
        assertThat(captured.get().messages().get(1).content()).doesNotContain("가".repeat(501));
    }

    private static PublicAnswerComposer composer(
            boolean enabled, Duration timeout, ProviderChatGatewayPort gateway) {
        return new PublicAnswerComposer(
                gateway, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new PublicChatLlmProperties(enabled, timeout, ModelProvider.ANTHROPIC, ""));
    }

    @Test
    void blankModelFallsBackToTheCheapestRegisteredModel() {
        assertThat(new PublicChatLlmProperties(
                false, Duration.ofSeconds(20), ModelProvider.ANTHROPIC, "  ").model())
                .isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void aNonPositiveTimeoutIsRejectedAtStartupRatherThanAtTheFirstQuery() {
        assertThatThrownBy(() -> new PublicChatLlmProperties(
                true, Duration.ZERO, ModelProvider.ANTHROPIC, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderChatResponse reply(String content) {
        return new ProviderChatResponse(
                ModelProvider.ANTHROPIC, "claude-haiku-4-5-20251001", content,
                List.of(), 100, 50, Duration.ofSeconds(1), ProviderFinishReason.COMPLETED);
    }

    private static ProductApiContract.RagQueryResponse answered() {
        return answered(citation(
                "진주남강유등축제",
                "[행사기간] 20261112 ~ 20261127\n[개요] 남강 일원에서 유등을 전시한다."));
    }

    private static ProductApiContract.RagQueryResponse answered(
            ProductApiContract.Citation citation) {
        return new ProductApiContract.RagQueryResponse(
                "1.0", TRACE, ID, ID, "ANSWERED", EXTRACTIVE, List.of(citation), ID, Instant.EPOCH);
    }

    private static ProductApiContract.Citation citation(String title, String excerpt) {
        return new ProductApiContract.Citation(
                "506926", title, URI.create("https://api-test.local/documents/506926"),
                excerpt, 0.71, "축제", null, null, null);
    }

    /** ENDED 근거 블록에는 [상태] 줄이 붙는다 — 규칙 1 아래에서 이 줄이 종료 사실의 유일한 근거다. */
    @Test
    void endedEventCarriesAStatusLineInItsGroundingBlock() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });
        ProductApiContract.Citation ended = new ProductApiContract.Citation(
                "506926", "안동국제탈춤페스티벌",
                URI.create("https://api-test.local/documents/506926"),
                "[행사기간] 20261003 ~ 20261018\n[개요] 탈춤 공연.", 0.71, "축제",
                "ENDED", LocalDate.of(2026, 10, 18), null);

        composer.rewrite("탈춤 축제 언제야?", answered(ended), null, PublicChatContract.AnswerStyle.DETAILED);

        assertThat(captured.get().messages().get(1).content())
                // 하이픈 날짜를 넘기면 규칙 2 때문에 답변에도 그대로 나온다.
                .contains("[상태] 종료된 행사 (2026년 10월 18일 종료)");
    }

    /** 종료되지 않은 문서의 블록에는 [상태] 줄이 없다 — 기존 근거 형식이 그대로다. */
    @Test
    void nonEndedGroundingBlockStaysUnchanged() {
        AtomicReference<ProviderChatRequest> captured = new AtomicReference<>();
        PublicAnswerComposer composer = composer(true, Duration.ofSeconds(20), request -> {
            captured.set(request);
            return reply("답변");
        });

        composer.rewrite("진주남강유등축제 언제 열려?", answered(), null, PublicChatContract.AnswerStyle.DETAILED);

        assertThat(captured.get().messages().get(1).content()).doesNotContain("[상태]");
    }
}
