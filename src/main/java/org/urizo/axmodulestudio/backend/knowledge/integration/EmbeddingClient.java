package org.urizo.axmodulestudio.backend.knowledge.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.knowledge.config.EmbeddingProperties;

/**
 * bge-m3 임베딩 서빙 API 클라이언트.
 *
 * <p>응답은 요청에 실어 보낸 id로 되찾는다. 순서로 맞추면 어긋나도 오류가 나지 않고 전건이
 * 조용히 오매칭되므로, 순서에 의존하지 않는다. 적재 전에 차원과 건수를 검증하고, 하나라도
 * 어긋나면 값을 쓰지 않고 실패시킨다.
 *
 * <p>부동소수점 값은 반올림하거나 자리를 줄이지 않는다. 응답 숫자를 double로 읽어 그대로
 * pgvector 리터럴로 만든다.
 */
@Component
@Profile("local-full")
public class EmbeddingClient {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    EmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public int batchSize() {
        return properties.batchSize();
    }

    /** 질의 텍스트 하나를 pgvector 리터럴로 임베딩한다. */
    public String queryVector(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        JsonNode response = send("/embed/query", body);
        requireDimension(response.path("dim").asInt(-1));
        return vectorLiteral(response.path("embedding"), "query");
    }

    /**
     * 문서 묶음을 임베딩한다. 반환 Map은 요청에 넣은 id를 키로 하며, 요청 건수와 크기가 같다.
     * 한 건이라도 응답에서 빠지면 예외로 실패시킨다.
     */
    public Map<String, String> batchVectors(List<Item> items) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode array = body.putArray("items");
        for (Item item : items) {
            ObjectNode node = array.addObject();
            node.put("id", item.id());
            node.put("text", item.text());
        }

        JsonNode response = send("/embed/batch", body);
        requireDimension(response.path("dim").asInt(-1));

        int count = response.path("count").asInt(-1);
        if (count != items.size()) {
            throw new EmbeddingException(
                    "Embedding batch returned " + count + " items for " + items.size() + " requested.");
        }

        JsonNode embeddings = response.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != items.size()) {
            throw new EmbeddingException("Embedding batch response size does not match the request.");
        }

        Map<String, String> byId = new LinkedHashMap<>(items.size());
        for (JsonNode entry : embeddings) {
            String id = entry.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new EmbeddingException("Embedding batch response is missing an id.");
            }
            if (byId.put(id, vectorLiteral(entry.path("embedding"), id)) != null) {
                throw new EmbeddingException("Embedding batch response repeated id " + id + ".");
            }
        }
        for (Item item : items) {
            if (!byId.containsKey(item.id())) {
                throw new EmbeddingException("Embedding batch response is missing id " + item.id() + ".");
            }
        }
        return byId;
    }

    private JsonNode send(String path, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EmbeddingException(
                        "Embedding service returned HTTP " + response.statusCode() + " for " + path + ".");
            }
            return objectMapper.readTree(response.body());
        }
        catch (IOException failure) {
            throw new EmbeddingException("Embedding service call failed for " + path + ".", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding service call was interrupted.", failure);
        }
    }

    private void requireDimension(int dimension) {
        if (dimension != properties.dimension()) {
            throw new EmbeddingException(
                    "Embedding service reported dimension " + dimension
                            + " but " + properties.dimension() + " is required.");
        }
    }

    private String vectorLiteral(JsonNode embedding, String label) {
        if (!embedding.isArray() || embedding.size() != properties.dimension()) {
            throw new EmbeddingException(
                    "Embedding for " + label + " has " + embedding.size()
                            + " values but " + properties.dimension() + " are required.");
        }
        StringBuilder literal = new StringBuilder(properties.dimension() * 12 + 2);
        literal.append('[');
        for (int i = 0; i < embedding.size(); i++) {
            JsonNode value = embedding.get(i);
            if (!value.isNumber()) {
                throw new EmbeddingException("Embedding for " + label + " contains a non-numeric value.");
            }
            if (i > 0) {
                literal.append(',');
            }
            // 반올림·자릿수 절단 없이 그대로 옮긴다.
            literal.append(Double.toString(value.doubleValue()));
        }
        return literal.append(']').toString();
    }

    public record Item(String id, String text) {
    }

    public static final class EmbeddingException extends RuntimeException {

        EmbeddingException(String message) {
            super(message);
        }

        EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
