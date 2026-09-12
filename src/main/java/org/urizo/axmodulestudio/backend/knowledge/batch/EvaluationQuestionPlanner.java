package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.urizo.axmodulestudio.backend.knowledge.config.EvaluationLlmProperties;

/**
 * 문서를 읽고 "사용자가 물어볼 법한 질문"을 만드는 출제자(AXMS-AI02-020).
 *
 * <p><b>출제만 한다.</b> 채점(검색·순위·지표)은 기존 평가 코드가 한다. 이 분리 때문에
 * 모델이 "어느 버전이 낫다"고 판단할 경로가 구조적으로 없다.
 *
 * <p><b>제목은 보내지 않는다.</b> 제목은 유출 검증의 기준으로만 쓴다 — 모델이 못 본 문장은
 * 베낄 수 없다. 본문에는 수집기가 붙인 {@code [라벨] 값} 줄이 포함돼 있어 메타데이터까지
 * 본문 하나로 전달된다.
 *
 * <p><b>모델은 긴 ID를 쓰지 않는다.</b> 문서를 번호로 보여 주고 번호로 답하게 한 뒤 코드가
 * {@code external_document_id}로 되돌린다. UUID·공고 ID를 베끼다 생기는 오타 경로를 없앤다.
 *
 * <p>검증을 통과하지 못한 문항은 고쳐 쓰지 않고 버린다 — 범위 밖 값을 잘라 쓰지 않는
 * {@link ChunkingStrategy#usable()}와 같은 태도다. 호출 실패는 배치 단위로 건너뛰고
 * 로그로 남긴다.
 */
@Component
@Profile("local-full")
public class EvaluationQuestionPlanner {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(EvaluationQuestionPlanner.class);

    /** 출제 표본 상한. 채점 표본(BuildEvaluation.MAX_SAMPLE)과 같은 값을 쓴다. */
    public static final int SAMPLE_DOCUMENTS = 50;
    /** 검증 통과 문항이 이보다 적으면 세트를 확정하지 않는다 — 반쪽 시험지는 시험지가 아니다. */
    public static final int MIN_CONFIRMABLE = 30;
    /** 한 호출에 싣는 문서 수. 문서 평균 473자 실측 기준 호출당 5천 자 안팎으로 묶인다. */
    static final int BATCH_DOCUMENTS = 10;
    /** 문서당 본문 상한. 오늘 최장 문서(929자)는 전문이 들어가고, 미래의 긴 문서만 자른다. */
    static final int CONTENT_CHARACTERS = 1_200;
    static final int MIN_QUESTION = 10;
    static final int MAX_QUESTION = 80;
    /** 제목과 이 길이 이상 연속 일치하면 제목을 베낀 것으로 본다. */
    static final int TITLE_RUN = 10;
    /** 본문과 이 길이 이상 연속 일치하면 원문 복사로 본다. */
    static final int CONTENT_RUN = 20;
    /** 프롬프트가 바뀌면 올린다. 세트에 기록되어 "어느 출제 기준으로 만든 세트인가"를 남긴다. */
    public static final String PROMPT_VERSION = "v1";

    static final String SYSTEM_PROMPT = """
            너는 검색 품질을 재기 위한 시험 문제를 만드는 도구다. 아래 규칙을 예외 없이 지킨다.

            1. 각 문서마다, 그 문서를 찾고 있는 사람이 실제로 물어볼 법한 질문을 정확히
               하나 만든다.
            2. 질문은 처지를 가진 사람의 말로 쓴다. 예: "창업한 지 1년 됐는데 받을 수 있는
               자금이 있나요?"
            3. 문서에 적힌 지역명·기관명·사업명을 그대로 옮겨 적지 않는다. 그 고유명사가
               없어도 내용으로 찾아지는지가 이 시험의 목적이다.
            4. 질문은 한국어 한 문장이고 10자 이상 80자 이하다.
            5. documentIndex는 주어진 문서 번호만 쓴다.
            6. JSON 객체 하나만 출력한다. 설명 문장이나 마크다운 기호를 덧붙이지 않는다.""";

    /** 출제 재료. {@code title}은 프롬프트에 실리지 않고 유출 검증에만 쓴다. */
    public record CandidateDocument(
            String externalDocumentId, String title, String content,
            String category, String contentDigest) { }

    /** 검증을 통과한 문항 하나. */
    public record GeneratedQuestion(String question, CandidateDocument source) { }

    private final ProviderChatGatewayPort gateway;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final EvaluationLlmProperties properties;

    EvaluationQuestionPlanner(
            ProviderChatGatewayPort gateway,
            Clock clock,
            ObjectMapper objectMapper,
            EvaluationLlmProperties properties) {
        this.gateway = gateway;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public String provider() {
        return properties.provider().name();
    }

    public String model() {
        return properties.model();
    }

    /**
     * 표본 문서들로 문항을 만든다. 배치 호출이 실패하면 그 배치만 건너뛴다.
     * 반환 목록이 충분한지는 호출자가 판단한다 — 여기서는 만들고 걸러낼 뿐이다.
     */
    public List<GeneratedQuestion> generate(List<CandidateDocument> candidates) {
        if (!properties.enabled() || candidates.isEmpty()) {
            return List.of();
        }
        List<GeneratedQuestion> accepted = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += BATCH_DOCUMENTS) {
            List<CandidateDocument> batch =
                    candidates.subList(start, Math.min(start + BATCH_DOCUMENTS, candidates.size()));
            JsonNode answer = ask(userMessage(batch));
            if (answer == null) {
                LOG.warn("Evaluation question batch skipped: documents {}..{}",
                        start + 1, start + batch.size());
                continue;
            }
            accepted.addAll(accept(batch, answer));
        }
        LOG.info("Evaluation questions generated: candidates={} accepted={}",
                candidates.size(), accepted.size());
        return List.copyOf(accepted);
    }

    /** 응답에서 문항을 꺼내 검증한다. 문서당 첫 유효 문항만 남긴다. */
    private static List<GeneratedQuestion> accept(List<CandidateDocument> batch, JsonNode answer) {
        Map<Integer, GeneratedQuestion> byIndex = new LinkedHashMap<>();
        for (JsonNode entry : answer.path("questions")) {
            int index = entry.path("documentIndex").asInt(0);
            if (index < 1 || index > batch.size() || byIndex.containsKey(index)) {
                continue;
            }
            String question = entry.path("question").asText("").strip();
            CandidateDocument source = batch.get(index - 1);
            if (usable(question, source)) {
                byIndex.put(index, new GeneratedQuestion(question, source));
            }
        }
        return List.copyOf(byIndex.values());
    }

    /**
     * 자동 검증. 전부 결정적 규칙이라 LLM을 다시 부르지 않는다.
     * 탈락 문항은 고치지 않고 버린다 — 스키마·지시를 오해한 응답이라는 신호다.
     */
    static boolean usable(String question, CandidateDocument source) {
        if (question.length() < MIN_QUESTION || question.length() > MAX_QUESTION) {
            return false;
        }
        if (sharesRun(question, source.title(), TITLE_RUN)) {
            return false;
        }
        if (sharesRun(question, source.content(), CONTENT_RUN)) {
            return false;
        }
        for (String institution : institutionValues(source.content())) {
            if (institution.length() >= 3 && question.contains(institution)) {
                return false;
            }
        }
        return true;
    }

    /** {@code question}의 {@code run}자 연속 구간이 {@code reference} 안에 그대로 있으면 참. */
    static boolean sharesRun(String question, String reference, int run) {
        if (reference == null || question.length() < run) {
            return false;
        }
        for (int index = 0; index + run <= question.length(); index++) {
            if (reference.contains(question.substring(index, index + run))) {
                return true;
            }
        }
        return false;
    }

    /** 본문에 결합된 {@code [소관기관] 값}·{@code [수행기관] 값} 줄의 값들. */
    static List<String> institutionValues(String content) {
        List<String> values = new ArrayList<>();
        if (content == null) {
            return values;
        }
        for (String line : content.split("\n")) {
            for (String label : new String[] {"[소관기관] ", "[수행기관] "}) {
                if (line.startsWith(label)) {
                    String value = line.substring(label.length()).strip();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }

    /** 실패하면 null. 호출자가 그 배치를 건너뛴다. */
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
        // 벽시계 상한을 따로 건다(ChunkingStrategyPlanner.ask와 같은 이유·같은 방식).
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
            LOG.warn("Evaluation question call failed: kind={} reason={}",
                    failure.getClass().getSimpleName(), cause.getMessage());
            call.cancel(true);
            return null;
        }
    }

    private JsonNode outputSchema() {
        var question = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        question.set("properties", objectMapper.createObjectNode()
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("documentIndex",
                        objectMapper.createObjectNode().put("type", "integer"))
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("question",
                        objectMapper.createObjectNode().put("type", "string")));
        question.set("required", objectMapper.createArrayNode()
                .add("documentIndex").add("question"));

        var questions = objectMapper.createObjectNode().put("type", "array");
        questions.set("items", question);

        var root = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        root.set("properties", objectMapper.createObjectNode()
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("questions", questions));
        root.set("required", objectMapper.createArrayNode().add("questions"));
        return root;
    }

    /** 번호 + 분류 + 본문(제목 제외). 번호는 이 배치 안에서 1부터다. */
    private static String userMessage(List<CandidateDocument> batch) {
        StringBuilder message = new StringBuilder();
        for (int index = 0; index < batch.size(); index++) {
            CandidateDocument document = batch.get(index);
            message.append("[문서 ").append(index + 1).append(']');
            if (document.category() != null && !document.category().isBlank()) {
                message.append(" (분류: ").append(document.category().strip()).append(')');
            }
            String content = document.content();
            message.append('\n')
                    .append(content.length() <= CONTENT_CHARACTERS
                            ? content : content.substring(0, CONTENT_CHARACTERS))
                    .append("\n\n");
        }
        return message.toString();
    }
}
