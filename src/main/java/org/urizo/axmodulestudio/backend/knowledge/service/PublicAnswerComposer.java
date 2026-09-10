package org.urizo.axmodulestudio.backend.knowledge.service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.knowledge.config.PublicChatLlmProperties;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 공개 질의 답변을 LLM 산문으로 다시 쓴다. 기본값은 꺼짐이며, 꺼져 있으면
 * {@code RagStore.composeAnswer}의 추출식 답변이 그대로 나간다.
 *
 * <p>검색·인용·거절은 건드리지 않는다. 이 클래스는 이미 확정된 근거로 문장만 다시
 * 쓴다. REFUSED는 {@code RagStore.query}가 {@code composeAnswer} 앞에서 반환하므로
 * 여기까지 오지 않는다 — 거절축 지표는 플래그와 무관하게 유지된다.
 *
 * <p><b>실패는 전부 추출식으로 폴백한다.</b> 인증 실패, 429, 타임아웃, 빈 응답 중
 * 무엇이든 원본 응답을 그대로 돌려준다. 촬영 중 사고에 화면이 깨지지 않는 것이
 * 이 배선의 안전망이고, 그래서 예외를 삼킨다.
 */
@Component
@Profile("local-full")
class PublicAnswerComposer {

    /**
     * Faithfulness 재측정 시 "어떤 프롬프트로 만든 답변인가"가 기록으로 필요하다.
     * 이 상수를 바꾸면 이전 측정치와 비교할 수 없다 — 바꿀 때는 측정 기록에 함께 남긴다.
     *
     * <p>제약 6종: 근거 한정 · 추가 금지 · 부족하면 부족하다고 말하기 · 길이 상한 ·
     * 종료 표시 양방향 고정 · 톤.
     *
     * <p><b>규칙 5(2026-09-10, axms-ai02-014).</b> 종료 문구를 모델 판단에 맡기지 않는다.
     * 규칙 1~4만 있으면 두 방향으로 어긋난다 — 종료된 행사를 근거로 받고도 종료를 말하지
     * 않거나(활성 버전 갱신 효과가 화면에서 사라진다), 근거의 행사기간만 보고 스스로 지난
     * 행사라고 단정한다(갱신 전 버전이 갱신 후처럼 보인다). 판단 기준을 데이터([상태] 줄)에
     * 고정해 두 경우를 모두 막는다. 기록된 Faithfulness 0.9633은 규칙 5 이전 프롬프트의
     * 측정치다(api-test `docs/baseline_faithfulness_prereg_0907.md` 9/10 정정 참조).
     *
     * <p><b>규칙 6(2026-09-10, axms-ai02-014).</b> 톤을 다정한 존댓말로 고정한다. 마지막 한
     * 문장은 규칙 5를 지키기 위한 것이다 — 부드럽게 쓰라는 지시만 있으면 모델이 "지난 행사인
     * 것 같아요"처럼 단정을 추측으로 흐려 종료 고지가 약해진다. 톤은 문장의 결이고 종료·날짜·
     * 요금은 근거가 정한 사실이므로, 두 층을 명시적으로 갈라 둔다.
     */
    static final String SYSTEM_PROMPT = """
            너는 관광 안내 챗봇이다. 아래 규칙을 예외 없이 지킨다.

            1. 제공된 근거 문서의 내용만 사용한다.
            2. 근거에 없는 정보를 추가하지 않는다. 일반 상식, 추측, 계산으로
               채우지 않는다. 날짜·장소·요금·연락처는 근거에 적힌 그대로 쓴다.
            3. 근거가 질문에 답하기에 부족하면, 지어내지 말고 부족하다고 말한다.
            4. 300자 안팎의 한국어 산문 한두 문단으로 답한다. 목록·표·머리말·
               맺음말을 쓰지 않고 답변 문장만 출력한다.
            5. 근거에 [상태] 줄이 있으면 그 행사가 종료됐다는 사실을 답변에 반드시
               포함한다. [상태] 줄이 없으면 종료 여부를 판단하거나 언급하지 않는다.
               오늘 날짜는 주어지지 않는다 — 행사 기간만 보고 지났다고 추측하지 않는다.
            6. 다정하고 상냥한 존댓말로 쓴다. 문장 끝은 "~입니다", "~해요" 같은 부드러운
               종결어미를 쓰고, 차갑거나 사무적인 어투("~함", "~됨", 개조식 나열)와
               이모지는 쓰지 않는다. 다만 근거가 정한 사실(종료 여부·날짜·요금)은
               부드럽게 쓰되 추측형으로 흐리지 않는다.""";

    private static final String USER_TEMPLATE = """
            [근거 문서]
            %s

            [질문]
            %s""";

    /** 근거 문서 하나가 응답에 싣는 excerpt 상한과 같다(RagStore.excerpt). */
    private static final int MAX_EXCERPT = 500;

    // 폴백은 조용히 성공한 것처럼 보인다 — 화면에는 추출식 답변이 정상으로 나온다.
    // 로그가 없으면 LLM이 한 번도 안 돌았다는 사실을 아무도 모른 채 촬영할 수 있다.
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(PublicAnswerComposer.class);

    private final ProviderChatGatewayPort gateway;
    private final Clock clock;
    private final PublicChatLlmProperties properties;

    PublicAnswerComposer(
            ProviderChatGatewayPort gateway, Clock clock, PublicChatLlmProperties properties) {
        this.gateway = gateway;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * ANSWERED 응답의 answer만 LLM 산문으로 교체한다. 꺼져 있거나, ANSWERED가
     * 아니거나, 호출이 실패하면 {@code grounded}를 그대로 돌려준다.
     */
    ProductApiContract.RagQueryResponse rewrite(
            String query, ProductApiContract.RagQueryResponse grounded) {
        if (!properties.enabled()
                || !"ANSWERED".equals(grounded.outcome())
                || grounded.citations().isEmpty()) {
            return grounded;
        }
        String prose = generate(query, grounded.citations());
        if (prose == null || prose.isBlank()) {
            LOG.warn("Public chat LLM answer fell back to the extractive answer: model={}",
                    properties.model());
            return grounded;
        }
        return new ProductApiContract.RagQueryResponse(
                grounded.schemaVersion(), grounded.traceId(), grounded.queryId(),
                grounded.conversationId(), grounded.outcome(), prose.trim(),
                grounded.citations(), grounded.knowledgeVersionId(), grounded.generatedAt());
    }

    /** 실패하면 null. 호출자는 추출식 답변으로 폴백한다. */
    private String generate(String query, List<ProductApiContract.Citation> citations) {
        ProviderChatRequest request = new ProviderChatRequest(
                properties.provider(),
                properties.model(),
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.SYSTEM, SYSTEM_PROMPT),
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER, userMessage(query, citations))),
                clock.instant().plus(properties.timeout()));
        // Gateway의 deadline은 시도 사이에서만 검사되므로 이미 나간 호출을 끊지 못한다.
        // 포털 요청이 provider 응답을 무한정 기다리지 않도록 벽시계 상한을 따로 건다.
        // ponytail: commonPool 사용. 공개 질의가 초당 수십 건이 되면 전용 Executor로 뺀다.
        CompletableFuture<ProviderChatResponse> call =
                CompletableFuture.supplyAsync(() -> gateway.chat(request));
        try {
            return call.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS).content();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return null;
        }
        catch (Exception failure) {
            // TimeoutException·ExecutionException(인증 실패·429·빈 응답). 처리는
            // 전부 같지만 R51 검수에서 어느 안전장치가 터졌는지는 구분돼야 한다.
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            LOG.warn("Public chat LLM call failed: kind={} reason={}",
                    failure.getClass().getSimpleName(), cause.getMessage());
            call.cancel(true);
            return null;
        }
    }

    private static String userMessage(
            String query, List<ProductApiContract.Citation> citations) {
        StringBuilder grounding = new StringBuilder();
        for (int index = 0; index < citations.size(); index++) {
            ProductApiContract.Citation citation = citations.get(index);
            String excerpt = citation.excerpt() == null ? "" : citation.excerpt();
            grounding.append(index + 1).append(". ").append(citation.title()).append('\n')
                    .append(excerpt.length() <= MAX_EXCERPT
                            ? excerpt : excerpt.substring(0, MAX_EXCERPT));
            // 종료된 행사는 근거에 명시한다. 규칙 1(근거만 사용) 아래에서 이 줄이 없으면
            // LLM이 종료 사실을 말할 수 없다. 2026-09-09 이후 형식 — 이전 faithfulness
            // 측정치(0.9633)는 이 줄이 없던 형식의 값이다(prereg 문서 정정 참조).
            if ("ENDED".equals(citation.eventStatus()) && citation.eventEndDate() != null) {
                grounding.append('\n').append("[상태] 종료된 행사 (")
                        .append(citation.eventEndDate()).append(" 종료)");
            }
            grounding.append("\n\n");
        }
        return USER_TEMPLATE.formatted(grounding.toString().stripTrailing(), query);
    }
}
