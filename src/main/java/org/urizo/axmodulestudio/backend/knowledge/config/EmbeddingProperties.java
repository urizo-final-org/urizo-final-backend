package org.urizo.axmodulestudio.backend.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임베딩 서빙 API 접속 설정.
 *
 * <p>서비스는 컨테이너 밖 호스트에서 동작하므로 기본 baseUrl은 {@code host.docker.internal}을
 * 가리킨다. 한 요청에 전량을 넣으면 read timeout에 걸리므로 배치 크기로 끊어 보낸다.
 */
@ConfigurationProperties("ax.knowledge.embedding")
public record EmbeddingProperties(
        String baseUrl,
        int batchSize,
        int dimension,
        Duration connectTimeout,
        Duration requestTimeout) {

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
