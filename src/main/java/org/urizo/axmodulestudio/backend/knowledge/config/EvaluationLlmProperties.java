package org.urizo.axmodulestudio.backend.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

/**
 * 골든 질문 평가셋 생성에 LLM을 쓸지에 대한 설정(AXMS-AI02-020).
 *
 * <p>청킹({@code chunking-llm})·챗봇({@code public-chat-llm}) 스위치와 <b>일부러 분리한다.</b>
 * 과금 시점이 다르다 — 이쪽은 빌드도 질의도 아니고, 관리자가 평가셋 생성을 명시적으로
 * 요청한 순간에만 지식베이스당 한 번 호출된다.
 *
 * <p>{@code timeout}은 호출 한 번의 벽시계 상한이다. 생성은 빌드 밖(관리 요청)에서 돌므로
 * 빌드 시간에는 영향이 없지만, 관리자를 무한정 기다리게 하지 않는다.
 */
@ConfigurationProperties("ax.knowledge.evaluation-llm")
public record EvaluationLlmProperties(
        boolean enabled,
        Duration timeout,
        ModelProvider provider,
        String model) {

    public EvaluationLlmProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Evaluation LLM timeout must be positive.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Evaluation LLM provider is required.");
        }
        model = model == null || model.isBlank()
                ? Stage2ProviderModels.ANTHROPIC_CHAT : model.trim();
    }
}
