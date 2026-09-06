package org.urizo.axmodulestudio.backend.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

/**
 * 공개 질의 답변을 LLM 산문으로 다시 쓸지에 대한 설정.
 *
 * <p>{@code enabled}가 이 제품에서 유료 provider 호출을 여는 유일한 스위치다. 꺼져 있으면
 * 추출식 답변이 그대로 나가므로 기록된 검색·인용·거절 기준선이 그대로 재현된다.
 *
 * <p>{@code timeout}은 벽시계 상한이다. Gateway의 deadline은 시도 사이에서만 검사돼
 * 이미 나간 호출을 끊지 못하므로, 포털 요청이 provider를 무한정 기다리지 않도록
 * 호출부에서 따로 건다. nginx {@code proxy_read_timeout} 60s보다 앞서 끊어야
 * 익명 호출자가 504 대신 추출식 답변을 받는다.
 *
 * <p>{@code provider}·{@code model}을 코드가 아닌 설정에 둔 이유는 이미지 재빌드가
 * 실측 4.9분이기 때문이다. 모델을 바꾸려고 재빌드를 돌리지 않는다.
 */
@ConfigurationProperties("ax.knowledge.public-chat-llm")
public record PublicChatLlmProperties(
        boolean enabled,
        Duration timeout,
        ModelProvider provider,
        String model) {

    public PublicChatLlmProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Public chat LLM timeout must be positive.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Public chat LLM provider is required.");
        }
        // 빈 값이면 등록된 모델 중 가장 싼 것으로 떨어진다. 운영자가 환경 변수를
        // 비워 두는 것이 "모델을 고르지 않았다"는 뜻이지 오류는 아니다.
        model = model == null || model.isBlank()
                ? Stage2ProviderModels.ANTHROPIC_CHAT : model.trim();
    }
}
