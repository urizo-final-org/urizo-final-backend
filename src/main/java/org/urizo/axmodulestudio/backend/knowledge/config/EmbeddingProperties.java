package org.urizo.axmodulestudio.backend.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임베딩 서빙 API 접속 설정.
 *
 * <p>서비스는 컨테이너 밖 호스트에서 동작하므로 기본 baseUrl은 {@code host.docker.internal}을
 * 가리킨다. 한 요청에 전량을 넣으면 read timeout에 걸리므로 배치 크기로 끊어 보낸다.
 *
 * <p>단건 질의와 배치는 소요 시간 규모가 다르므로 요청 타임아웃을 나눈다. 배치용
 * {@code requestTimeout}을 단건 질의에 그대로 쓰면 실측 수백 ms짜리 호출이 수 분을
 * 기다린다. 익명 공개 질의 경로에서는 그동안 요청 스레드가 묶인다.
 */
@ConfigurationProperties("ax.knowledge.embedding")
public record EmbeddingProperties(
        String baseUrl,
        int batchSize,
        int dimension,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration queryRequestTimeout) {

    public EmbeddingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding baseUrl is required.");
        }
        if (batchSize < 1 || batchSize > 256) {
            throw new IllegalArgumentException("Embedding batchSize must be between 1 and 256.");
        }
        if (dimension < 1) {
            throw new IllegalArgumentException("Embedding dimension must be positive.");
        }
    }
}
