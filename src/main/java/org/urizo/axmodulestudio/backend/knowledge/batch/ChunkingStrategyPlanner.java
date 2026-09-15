package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.knowledge.config.ChunkingLlmProperties;

/**
 * 수집한 문서의 생김새를 LLM에 보여 주고 청킹 규칙을 받아 온다(AXMS-AI02-018).
 *
 * <p><b>LLM이 파이프라인을 실행하지 않는다.</b> 모델은 상한·겹침 두 숫자와 근거 한 줄만
 * 정하고, 자르기·임베딩·색인은 기존 코드가 그대로 한다. 모델이 본문을 다시 쓰게 두면
 * 색인된 근거가 원문과 달라져 인용이 거짓이 된다.
 *
 * <p><b>표본만 보낸다.</b> 500건 전문은 요청 상한(65,536자)에도, 출력 토큰에도 들어가지
 * 않는다. 길이 분포와 앞 3건의 머리말만으로 "짧은 공고 모음인지 긴 산문인지"는 판별된다.
 *
 * <p><b>실패는 전부 폴백이다.</b> 꺼져 있거나, 호출이 실패하거나, 값이 범위 밖이면
 * {@link ChunkingStrategy#WHOLE_DOCUMENT}로 돌아간다 — 빌드가 죽는 것보다 이전과 같은
 * 방식으로 끝나는 편이 낫다. 폴백은 로그로 남긴다. 조용히 폴백하면 "LLM이 한 번도 안
 * 돌았다"를 아무도 모른 채 시연한다.
 */
@Component
@Profile("local-full")
public class ChunkingStrategyPlanner {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ChunkingStrategyPlanner.class);

    /** 표본 문서 수와 각 표본에서 보여 줄 머리말 길이. 프롬프트 길이를 예측 가능하게 묶는다. */
    private static final int SAMPLE_DOCUMENTS = 3;
    private static final int SAMPLE_CHARACTERS = 600;

    static final String SYSTEM_PROMPT = """
            너는 RAG 색인의 청킹 규칙을 정하는 도구다. 아래 규칙을 예외 없이 지킨다.

            1. 주어진 통계와 표본만 보고 판단한다. 문서를 다시 쓰거나 요약하지 않는다.
            2. maxCharacters는 한 청크의 상한이다. 문서 대부분이 이 값보다 짧으면 자르지
               않는 것과 같아지므로, 중앙값보다 크게 잡으면 의미가 없다.
            3. overlapCharacters는 앞 청크의 꼬리를 다음 청크에 겹쳐 싣는 길이다.
               maxCharacters의 절반을 넘지 않는다. 겹칠 이유가 없으면 0으로 둔다.
            4. maxCharacters는 200 이상 4000 이하여야 한다.
            5. reason은 왜 그 값인지 한국어 한 문장으로 적는다. 나중에 사람이 버전을
               비교할 때 읽는 유일한 근거다.
            6. JSON 객체 하나만 출력한다. 설명 문장이나 마크다운 기호를 덧붙이지 않는다.""";

    private final ProviderChatGatewayPort gateway;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ChunkingLlmProperties properties;

    ChunkingStrategyPlanner(
            ProviderChatGatewayPort gateway,
            Clock clock,
            ObjectMapper objectMapper,
            ChunkingLlmProperties properties) {
        this.gateway = gateway;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 수집된 본문들로 전략을 정한다. 실패·비활성·범위 밖은 전부 {@code WHOLE_DOCUMENT}다.
     *
     * @param contents 이번 버전의 문서 본문. 호출자가 이미 읽어 둔 것을 그대로 쓴다.
     */
    public ChunkingStrategy plan(List<String> contents) {
        if (!properties.enabled() || contents.isEmpty()) {
            return ChunkingStrategy.WHOLE_DOCUMENT;
        }
        JsonNode answer = ask(userMessage(contents));
        if (answer == null) {
            return ChunkingStrategy.WHOLE_DOCUMENT;
        }
        String reason = answer.path("reason").asText("");
        if (reason.isBlank()) {
            LOG.warn("Chunking strategy fell back: the model returned no reason.");
            return ChunkingStrategy.WHOLE_DOCUMENT;
        }
        ChunkingStrategy strategy = new ChunkingStrategy(
                answer.path("maxCharacters").asInt(0),
                answer.path("overlapCharacters").asInt(0),
                reason.trim());
        if (!strategy.usable()) {
            // 범위 밖 값을 잘라서 쓰지 않는다 — 스키마를 오해한 응답이라는 신호다.
            LOG.warn("Chunking strategy fell back: max={} overlap={} is out of range.",
                    strategy.maxCharacters(), strategy.overlapCharacters());
            return ChunkingStrategy.WHOLE_DOCUMENT;
        }
        LOG.info("Chunking strategy decided: max={} overlap={}",
                strategy.maxCharacters(), strategy.overlapCharacters());
        return strategy;
    }

    /** 실패하면 null. 호출자가 폴백한다. */
    private JsonNode ask(String userMessage) {
        ProviderChatRequest request = new ProviderChatRequest(
                properties.provider(),
                properties.model(),
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.SYSTEM, SYSTEM_PROMPT),
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER, userMessage)),
                List.of(),
                ProviderResponseFormat.jsonSchema(outputSchema()),
                clock.instant().plus(properties.timeout()),
                org.urizo.axmodulestudio.backend.integration.ai.gateway.InferenceSettings.none());
        // Gateway deadline은 시도 사이에서만 검사되므로 이미 나간 호출을 끊지 못한다.
        // 빌드가 provider 응답을 무한정 기다리지 않도록 벽시계 상한을 따로 건다
        // (PublicAnswerComposer.generate와 같은 이유·같은 방식).
        CompletableFuture<String> call = CompletableFuture.supplyAsync(
                () -> gateway.chat(request).content());
        try {
            String content = call.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return content == null || content.isBlank() ? null : objectMapper.readTree(content);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return null;
        }
        catch (Exception failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            LOG.warn("Chunking strategy call failed: kind={} reason={}",
                    failure.getClass().getSimpleName(), cause.getMessage());
            call.cancel(true);
            return null;
        }
    }

    private JsonNode outputSchema() {
        return objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false)
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        objectMapper.createObjectNode()
                                .<com.fasterxml.jackson.databind.node.ObjectNode>set("maxCharacters",
                                        objectMapper.createObjectNode().put("type", "integer"))
                                .<com.fasterxml.jackson.databind.node.ObjectNode>set("overlapCharacters",
                                        objectMapper.createObjectNode().put("type", "integer"))
                                .<com.fasterxml.jackson.databind.node.ObjectNode>set("reason",
                                        objectMapper.createObjectNode().put("type", "string")))
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                        objectMapper.createArrayNode()
                                .add("maxCharacters").add("overlapCharacters").add("reason"));
    }

    /**
     * 통계 + 표본 머리말. 전문을 싣지 않는 이유는 클래스 주석에 있다.
     *
     * <p>중앙값을 함께 주는 것은 평균만으로는 "대부분 짧은데 몇 건이 아주 길다"를 구분할 수
     * 없기 때문이다. 그 구분이 상한 결정을 좌우한다.
     */
    private static String userMessage(List<String> contents) {
        int[] lengths = contents.stream().mapToInt(String::length).sorted().toArray();
        int median = lengths[lengths.length / 2];
        StringBuilder message = new StringBuilder()
                .append("[문서 통계]\n")
                .append("건수: ").append(lengths.length).append('\n')
                .append("본문 길이 최소: ").append(lengths[0]).append('\n')
                .append("본문 길이 중앙값: ").append(median).append('\n')
                .append("본문 길이 최대: ").append(lengths[lengths.length - 1]).append("\n\n")
                .append("[표본 머리말]\n");
        contents.stream().limit(SAMPLE_DOCUMENTS).forEach(content -> message
                .append("- ")
                .append(content.length() <= SAMPLE_CHARACTERS
                        ? content : content.substring(0, SAMPLE_CHARACTERS))
                .append('\n'));
        return message.toString();
    }
}
