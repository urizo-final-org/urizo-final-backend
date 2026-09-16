package org.urizo.axmodulestudio.backend.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

/**
 * 빌드의 CHUNK 단계에서 LLM에게 청킹 규칙을 물을지에 대한 설정(AXMS-AI02-018).
 *
 * <p>공개 챗봇 스위치({@code ax.knowledge.public-chat-llm})와 <b>일부러 분리한다.</b>
 * 둘은 성격이 다르다 — 챗봇은 방문자 질의마다 과금되고, 이쪽은 빌드 한 번에 한 번이다.
 * 하나로 묶으면 "챗봇을 켜려다 빌드 비용까지 열거나" 그 반대가 된다.
 *
 * <p>{@code timeout}은 벽시계 상한이다. 빌드가 provider를 무한정 기다리면 8분짜리 작업이
 * 끝나지 않는다. 넘기면 전략 없이(문서당 1청크) 계속 진행한다 — 빌드를 죽이지 않는다.
 */
@ConfigurationProperties("ax.knowledge.chunking-llm")
public record ChunkingLlmProperties(
        boolean enabled,
        Duration timeout,
        ModelProvider provider,
        String model) {

    public ChunkingLlmProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Chunking LLM timeout must be positive.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Chunking LLM provider is required.");
        }
        model = model == null || model.isBlank()
                ? Stage2ProviderModels.ANTHROPIC_CHAT : model.trim();
    }
}
