package org.urizo.axmodulestudio.backend.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

/**
 * 품질 진단 에이전트 설정(AXMS-AI02-027).
 *
 * <p>다른 세 스위치(청킹·챗봇·평가셋)와 또 분리한다. 과금 시점이 또 다르다 — 이쪽은
 * 관리자가 "왜 점수가 낮은가"를 물은 순간에만, 조사 한 번당 여러 번 호출된다.
 *
 * <p>{@code maxToolCalls}와 {@code budget}은 시연 안정성 장치다. 모델이 스스로 도구를
 * 고르는 구조라 호출 수가 예측되지 않으므로, 관리자를 무한정 기다리게 하지 않도록
 * 횟수와 벽시계를 둘 다 막는다. 상한에 걸리면 실패가 아니라 <b>그때까지 모은 것으로
 * 진단</b>한다 — 조사가 덜 끝났다고 화면을 비워 두는 것보다 낫다.
 *
 * @param maxToolCalls 조사 단계에서 허용하는 도구 호출 총 횟수
 * @param budget 조사 시작부터 최종 진단까지의 벽시계 상한
 * @param timeout 모델 호출 한 번의 벽시계 상한
 */
@ConfigurationProperties("ax.knowledge.diagnosis-llm")
public record DiagnosisLlmProperties(
        boolean enabled,
        Duration timeout,
        Duration budget,
        int maxToolCalls,
        ModelProvider provider,
        String model) {

    public DiagnosisLlmProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Diagnosis LLM timeout must be positive.");
        }
        if (budget == null || budget.compareTo(timeout) < 0) {
            throw new IllegalArgumentException(
                    "Diagnosis budget must be at least one model timeout.");
        }
        if (maxToolCalls < 1 || maxToolCalls > 20) {
            throw new IllegalArgumentException("Diagnosis tool call bound must be 1..20.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Diagnosis LLM provider is required.");
        }
        model = model == null || model.isBlank()
                ? Stage2ProviderModels.ANTHROPIC_CHAT : model.trim();
    }
}
