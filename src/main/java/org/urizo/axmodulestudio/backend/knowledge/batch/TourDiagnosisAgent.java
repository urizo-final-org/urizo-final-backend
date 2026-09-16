package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.InferenceSettings;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.knowledge.config.DiagnosisLlmProperties;

/**
 * 관광 RAG 버전의 품질이 왜 그 점수인지 스스로 조사하는 에이전트(AXMS-AI02-027).
 *
 * <p><b>모델이 다음 수를 정한다.</b> 어떤 도구를 어떤 순서로 부를지, 비교 버전을 볼지,
 * 실패 문항 원문을 몇 건 열어볼지 — 코드가 정해 주지 않는다. 프롬프트는 권장 순서만
 * 제시하고 강제하지 않는다. 그래서 이것은 한 번의 LLM 호출이 아니라 루프다.
 *
 * <p><b>provider의 tool calling을 쓰지 않는다.</b> {@code ProviderToolDefinition}은 이름이
 * {@code McpPlatformContract}의 허용 목록에 있어야 하는데, 그 목록은 MCP Server 저장소와
 * 짝을 이루는 공통 계약이다(coding 7종·cms 6종). 관광 진단 도구 세 개를 넣으려면 저장소
 * 둘을 함께 바꿔야 하고 통합 담당자 승인이 필요하다. 대신 <b>구조화 출력으로 다음 행동을
 * 받아 코드가 실행하는</b> 방식을 쓴다 — 기존 두 플래너가 이미 쓰는 경로이고, 공통 계약
 * 변경이 0건이다. 모델이 스스로 도구를 고른다는 성질은 그대로다.
 *
 * <p><b>상한에 걸려도 실패로 끝내지 않는다.</b> 호출 횟수·벽시계를 넘으면 그때까지 모은
 * 조사 결과로 진단을 내린다. 조사가 덜 끝났다고 화면을 비워 두는 것보다, 근거가 얕다는
 * 사실과 함께 결론을 보여 주는 편이 관리자에게 쓸모 있다.
 */
@Component
@Profile("local-full")
public class TourDiagnosisAgent {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TourDiagnosisAgent.class);

    /** 프롬프트가 바뀌면 올린다. 어떤 기준으로 내린 진단인지 결과에 남는다. */
    /** v2에서 화면 문장을 존댓말로 통일하고 각 칸의 길이를 묶었다. 조사 흐름은 그대로다. */
    public static final String PROMPT_VERSION = "v2";

    static final String SYSTEM_PROMPT = """
            너는 관광 정보 RAG의 검색 품질이 왜 그 점수인지 조사하는 도구다.

            도구를 한 번에 하나씩 골라 부르고, 결과를 보고 다음 도구를 정한다.
            충분히 알았다고 판단하면 조사를 멈추고 진단을 내린다.

            쓸 수 있는 도구:
            1. version_overview(knowledge_version_id) — 문서 수, 본문 길이, 설명·사진·
               행사일이 채워진 문서 수, 저장된 점수
            2. score_questions(knowledge_version_id) — 골든 질문을 다시 채점해 실패한
               문항과 그 정답 문서를 돌려준다. 느리다(수 초). 한 버전에 한 번이면 충분하다
            3. inspect_documents(knowledge_version_id, document_ids) — 문서 원문. 최대 10건

            권장 순서는 있지만 따를 의무는 없다. 대상 버전 개요 → 채점 → 실패 문항 원문 →
            (필요하면) 비교 버전 개요. 비교가 결론에 도움이 된다고 판단하면 다른 버전을
            봐도 된다.

            출력 형식 — 다섯 필드를 항상 전부 채운다. 쓰지 않는 자리는 빈 값으로 둔다.
            - action: "call_tool" 또는 "conclude"
            - tool: 위 셋 중 하나. conclude면 빈 문자열
            - reason: 지금 이 선택을 하는 이유 한 문장
            - knowledge_version_id: 조사할 버전. conclude면 빈 문자열
            - document_ids: inspect_documents일 때만 문서 id 배열, 그 외에는 빈 배열

            규칙:
            - 한 번에 도구 하나만 고른다.
            - reason은 왜 지금 그 도구를 부르는지 한국어 한 문장으로 적는다. 관리자가
              화면에서 읽는 문장이므로 "-합니다"·"-입니다" 체로 60자 이내로 쓴다.
            - 도구가 error를 돌려주면 같은 호출을 반복하지 말고 다른 수를 찾거나 끝낸다.
            - 진단의 evidence는 전부 도구가 돌려준 수치에서 나와야 한다. 도구가 주지 않은
              숫자를 지어내지 않는다.
            - reasoning에는 실패한 문항 하나를 실제로 인용해 왜 못 찾았는지 설명한다.
            - JSON 객체 하나만 출력한다. 마크다운을 붙이지 않는다.""";

    private final ProviderChatGatewayPort gateway;
    private final TourDiagnosisTools tools;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final DiagnosisLlmProperties properties;

    TourDiagnosisAgent(
            ProviderChatGatewayPort gateway,
            TourDiagnosisTools tools,
            Clock clock,
            ObjectMapper objectMapper,
            DiagnosisLlmProperties properties) {
        this.gateway = gateway;
        this.tools = tools;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** 조사 한 단계. 화면이 이 목록을 그대로 순서대로 보여 준다. */
    public record Step(int order, String tool, String reason, boolean failed, JsonNode result) { }

    /** 조사 + 진단의 결과 전체. */
    public record Diagnosis(
            List<Step> steps, JsonNode verdict, String stopReason, String promptVersion) { }

    /**
     * {@code versionId}의 품질을 조사하고 진단한다.
     *
     * @param versionId 관광 지식베이스의 버전. 다른 도메인이면 도구가 스스로 거절한다.
     */
    public Diagnosis diagnose(UUID versionId) {
        Instant deadline = clock.instant().plus(properties.budget());
        List<ProviderChatMessage> messages = new ArrayList<>();
        messages.add(ProviderChatMessage.plain(ProviderChatMessage.Role.SYSTEM, SYSTEM_PROMPT));
        messages.add(ProviderChatMessage.plain(ProviderChatMessage.Role.USER,
                "대상 버전: " + versionId + "\n이 버전의 검색 품질이 왜 그 점수인지 조사해라."));

        List<Step> steps = new ArrayList<>();
        Map<String, JsonNode> cache = new HashMap<>();
        String stopReason = "MODEL_CONCLUDED";

        while (true) {
            if (steps.size() >= properties.maxToolCalls()) {
                stopReason = "TOOL_CALL_LIMIT";
                break;
            }
            if (!clock.instant().isBefore(deadline)) {
                stopReason = "BUDGET_EXHAUSTED";
                break;
            }
            JsonNode decision = ask(messages, actionSchema(), deadline);
            if (decision == null) {
                stopReason = steps.isEmpty() ? "MODEL_UNAVAILABLE" : "MODEL_FAILED";
                break;
            }
            if (!"call_tool".equals(decision.path("action").asText(""))) {
                // 모델이 조사를 끝내겠다고 했다. 진단은 아래에서 따로 받는다.
                break;
            }

            String tool = decision.path("tool").asText("");
            JsonNode arguments = argumentsOf(decision);
            String reason = decision.path("reason").asText("").strip();
            String key = tool + ":" + arguments;
            // 같은 호출을 반복하면 다시 실행하지 않는다. 횟수는 그대로 센다 — 반복 자체가
            // 모델이 답을 못 찾고 있다는 신호이고, 상한이 그 신호를 끊어야 한다.
            JsonNode result = cache.containsKey(key)
                    ? markCached(cache.get(key)) : tools.call(tool, arguments);
            cache.putIfAbsent(key, result);

            boolean failed = result.hasNonNull("error");
            steps.add(new Step(steps.size() + 1, tool, reason, failed, result));
            messages.add(ProviderChatMessage.plain(ProviderChatMessage.Role.ASSISTANT,
                    decision.toString()));
            messages.add(ProviderChatMessage.plain(ProviderChatMessage.Role.USER,
                    "도구 결과:\n" + result));
        }

        JsonNode verdict = conclude(messages, steps, stopReason, deadline);
        LOG.info("Tour diagnosis finished: version={} steps={} stop={}",
                versionId, steps.size(), stopReason);
        return new Diagnosis(List.copyOf(steps), verdict, stopReason, PROMPT_VERSION);
    }

    /** 조사를 접고 진단만 받는다. 도구 없이 구조화 출력만 쓴다. */
    private JsonNode conclude(
            List<ProviderChatMessage> messages, List<Step> steps,
            String stopReason, Instant deadline) {
        List<ProviderChatMessage> closing = new ArrayList<>(messages);
        closing.add(ProviderChatMessage.plain(ProviderChatMessage.Role.USER,
                "조사를 마쳐라. 지금까지 도구가 돌려준 수치만 근거로 진단을 내려라. "
                        // 네 칸 모두 관리자가 화면에서 그대로 읽는 문장이다. 화면의 다른
                        // 문구가 존댓말이므로 여기만 평서체면 한 카드 안에서 말투가 갈린다.
                        + "네 칸(verdict·evidence·reasoning·recommendation) 모두 관리자가 "
                        + "화면에서 읽는 문장이므로 \"-합니다\"·\"-입니다\" 체로 쓴다. "
                        // verdict는 화면에서 굵은 한 줄로 쓰인다. 길면 줄이 접혀 카드가
                        // 무너지고, 관리자가 한눈에 읽어야 할 핵심이 묻힌다.
                        + "verdict는 원인 하나만 담은 한 문장으로, 40자 이상 60자 이하로 쓴다. "
                        + "숫자·근거·권고를 verdict에 넣지 않는다 — 그건 아래 칸의 몫이다. "
                        + "evidence는 3~5개, 각 50자 이내의 짧은 한 문장으로 쓰고 전부 도구가 "
                        + "준 숫자를 담는다. "
                        // 한 문단이 길어지면 화면에서 여러 줄로 접혀 진단이 묻힌다.
                        + "reasoning에는 실패한 문항 하나를 실제로 인용하되 120자 이내로 쓴다. "
                        + "recommendation은 무엇을 하면 되는지 80자 이내 한 문장으로 쓴다. "
                        + "confidence는 HIGH·MEDIUM·LOW 중 하나로 쓴다."
                        + ("MODEL_CONCLUDED".equals(stopReason) ? ""
                        : " 조사가 상한에 걸려 중단됐다(" + stopReason
                                + "). 근거가 얕으면 confidence를 낮춰라.")));
        JsonNode verdict = ask(closing, verdictSchema(), deadline.plusSeconds(30));
        if (verdict != null && verdict.hasNonNull("verdict")) {
            return verdict;
        }
        // 진단 호출까지 실패하면 빈 화면 대신 무엇을 했는지라도 남긴다.
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.put("verdict", "진단을 완성하지 못했습니다.");
        fallback.putArray("evidence")
                .add("조사 " + steps.size() + "단계를 수행했으나 결론 생성에 실패했습니다.");
        fallback.put("reasoning", "");
        fallback.put("recommendation", "잠시 후 다시 시도하세요.");
        fallback.put("confidence", "LOW");
        return fallback;
    }

    /** 실패하면 null. 호출자가 그 의미를 정한다. */
    private JsonNode ask(
            List<ProviderChatMessage> messages, JsonNode schema, Instant deadline) {
        ProviderChatRequest request = new ProviderChatRequest(
                properties.provider(), properties.model(), List.copyOf(messages), List.of(),
                ProviderResponseFormat.jsonSchema(schema), deadline, InferenceSettings.none());
        // Gateway deadline은 시도 사이에서만 검사되므로 이미 나간 호출을 끊지 못한다.
        // 벽시계 상한을 따로 건다(기존 두 플래너와 같은 이유·같은 방식).
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
            LOG.warn("Tour diagnosis call failed: kind={} reason={}",
                    failure.getClass().getSimpleName(), cause.getMessage());
            call.cancel(true);
            return null;
        }
    }

    private JsonNode markCached(JsonNode result) {
        ObjectNode copy = result.deepCopy();
        copy.put("cached", true);
        return copy;
    }

    /**
     * 조사 단계의 출력 스키마: 도구를 부르거나, 끝내거나.
     *
     * <p><b>{@code enum}을 쓰지 않는다.</b> {@link ProviderJsonSchema}가 받는 어휘는
     * object(type·properties·required·additionalProperties) · array(type·items) ·
     * string/integer(필드 정확히 1개)뿐이다. 그래서 허용 값은 스키마가 아니라 프롬프트로
     * 지시하고, 코드가 판정한다 — 모르는 도구 이름은 도구 실행기가 UNKNOWN_TOOL로 돌려주고,
     * action이 call_tool이 아니면 루프가 조사를 끝낸다.
     */
    JsonNode actionSchema() {
        // 인자를 중첩 객체로 두지 않고 평탄하게 편다. strict 출력은 모든 object가
        // properties·required·additionalProperties를 갖고 required가 전체 속성과 같기를
        // 요구해서, "도구마다 모양이 다른 자유 객체"를 표현할 방법이 없다.
        ObjectNode root = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.set("action", objectMapper.createObjectNode().put("type", "string"));
        properties.set("tool", objectMapper.createObjectNode().put("type", "string"));
        properties.set("reason", objectMapper.createObjectNode().put("type", "string"));
        properties.set("knowledge_version_id",
                objectMapper.createObjectNode().put("type", "string"));
        properties.set("document_ids", objectMapper.createObjectNode()
                .put("type", "array")
                .<ObjectNode>set("items",
                        objectMapper.createObjectNode().put("type", "string")));
        // strict 출력은 전 속성이 required다. 쓰지 않는 자리는 모델이 빈 값으로 채운다.
        root.set("required", objectMapper.createArrayNode()
                .add("action").add("tool").add("reason")
                .add("knowledge_version_id").add("document_ids"));
        return root;
    }

    /** 평탄한 응답을 도구가 받는 인자 모양으로 되돌린다. */
    private JsonNode argumentsOf(JsonNode decision) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("knowledge_version_id",
                decision.path("knowledge_version_id").asText(""));
        arguments.set("document_ids", decision.path("document_ids").isArray()
                ? decision.path("document_ids").deepCopy()
                : objectMapper.createArrayNode());
        return arguments;
    }

    /** 최종 진단의 출력 스키마. */
    private JsonNode verdictSchema() {
        ObjectNode root = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.set("verdict", objectMapper.createObjectNode().put("type", "string"));
        properties.set("evidence", objectMapper.createObjectNode()
                .put("type", "array")
                .<ObjectNode>set("items",
                        objectMapper.createObjectNode().put("type", "string")));
        properties.set("reasoning", objectMapper.createObjectNode().put("type", "string"));
        properties.set("recommendation", objectMapper.createObjectNode().put("type", "string"));
        // 값은 HIGH·MEDIUM·LOW 셋 중 하나를 프롬프트가 지시한다(위 actionSchema 주석 참조).
        properties.set("confidence", objectMapper.createObjectNode().put("type", "string"));
        root.set("required", objectMapper.createArrayNode()
                .add("verdict").add("evidence").add("reasoning")
                .add("recommendation").add("confidence"));
        return root;
    }
}
