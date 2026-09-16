package org.urizo.axmodulestudio.backend.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * 증분 평가셋의 두 갈래가 조용히 틀리기 쉬워서 여기 묶어 둔다.
 *
 * <p>문항 id가 겹치면 채점이 같은 문항을 두 번 세고, 덮인 문서를 빠뜨리면 한 문서에 문항이
 * 둘 생겨 그 문서가 점수에서 두 번 계산된다. 둘 다 점수는 그럴듯하게 나오고 로그도 조용하다.
 */
class LocalEvaluationSetIncrementTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode set(String json) {
        try {
            return JSON.readTree(json);
        }
        catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 확정 문항 뒤부터 번호가 이어진다 — 1차가 q50까지 썼으면 증분은 q51부터다. */
    @Test
    void 증분_문항은_기존_최대번호_다음부터_붙는다() {
        JsonNode confirmed = set("""
                { "questions": [
                    { "id": "q01", "expectedExternalDocumentId": "A" },
                    { "id": "q50", "expectedExternalDocumentId": "B" },
                    { "id": "q07", "expectedExternalDocumentId": "C" } ] }
                """);

        assertThat(LocalEvaluationSetController.nextQuestionNumber(confirmed)).isEqualTo(51);
    }

    /** 형식이 다른 id가 섞여도 번호 계산이 죽지 않는다. 세트는 사람이 손댈 수 있는 JSON이다. */
    @Test
    void 번호로_읽을_수_없는_id는_건너뛴다() {
        JsonNode odd = set("""
                { "questions": [
                    { "id": "q03", "expectedExternalDocumentId": "A" },
                    { "id": "legacy-question", "expectedExternalDocumentId": "B" },
                    { "id": "", "expectedExternalDocumentId": "C" } ] }
                """);

        assertThat(LocalEvaluationSetController.nextQuestionNumber(odd)).isEqualTo(4);
    }

    /** 세트가 비어 있으면 q01부터다. */
    @Test
    void 빈_세트는_1번부터() {
        assertThat(LocalEvaluationSetController.nextQuestionNumber(set("{}"))).isEqualTo(1);
    }

    /**
     * <b>대기 중 증분도 "이미 덮은 문서"에 든다.</b> 여기를 빠뜨리면 증분을 두 번 돌렸을 때
     * 같은 문서로 문항이 두 개 생기고, 그 문서가 점수에서 두 번 세어진다.
     */
    @Test
    void 덮인_문서에는_확정분과_대기중_증분이_모두_들어간다() {
        JsonNode both = set("""
                { "questions": [
                    { "id": "q01", "expectedExternalDocumentId": "127813" },
                    { "id": "q02", "expectedExternalDocumentId": "127490" } ],
                  "pendingIncrement": { "questions": [
                    { "id": "q03", "expectedExternalDocumentId": "776934" } ] } }
                """);

        assertThat(LocalEvaluationSetController.coveredDocumentIds(both))
                .containsExactlyInAnyOrder("127813", "127490", "776934");
    }

    /** 정답 문서가 비어 있는 줄은 "덮었다"고 치지 않는다 — 빈 문자열이 후보를 통째로 막는다. */
    @Test
    void 빈_문서번호는_덮은_것으로_세지_않는다() {
        JsonNode blank = set("""
                { "questions": [
                    { "id": "q01", "expectedExternalDocumentId": "127813" },
                    { "id": "q02" } ] }
                """);

        assertThat(LocalEvaluationSetController.coveredDocumentIds(blank))
                .containsExactly("127813");
    }

    /** 증분이 아직 없으면 덮인 문서는 확정 문항만이다. */
    @Test
    void 대기중_증분이_없어도_동작한다() {
        JsonNode plain = set("""
                { "questions": [ { "id": "q01", "expectedExternalDocumentId": "127813" } ] }
                """);

        assertThat(LocalEvaluationSetController.coveredDocumentIds(plain)).containsExactly("127813");
    }

    private static final java.time.Instant AT = java.time.Instant.parse("2026-09-16T09:00:00Z");

    private static JsonNode pendingSet() {
        return set("""
                { "status": "CONFIRMED", "setVersion": 1,
                  "questions": [ { "id": "q01", "expectedExternalDocumentId": "A" } ],
                  "pendingIncrement": {
                    "increment": 2, "status": "DRAFT",
                    "generatedAt": "2026-09-16T08:00:00Z",
                    "sourceVersionId": "11111111-1111-1111-1111-111111111111",
                    "trigger": { "alreadyCovered": 50, "uncoveredSampled": 50 },
                    "questions": [
                      { "id": "q02", "expectedExternalDocumentId": "B", "origin": { "increment": 2 } },
                      { "id": "q03", "expectedExternalDocumentId": "C", "origin": { "increment": 2 } } ] } }
                """);
    }

    /** 확정하면 증분 문항이 채점 대상(questions)으로 들어오고, 대기 칸은 비워진다. */
    @Test
    void 확정하면_증분_문항이_채점_대상으로_편입된다() {
        ObjectNode next = LocalEvaluationSetController.merged(pendingSet(), AT);

        assertThat(next.withArray("questions")).hasSize(3);
        assertThat(next.has("pendingIncrement")).isFalse();
        // 확정 상태는 그대로 — 편입은 세트의 상태를 바꾸는 일이 아니다.
        assertThat(next.path("status").asText()).isEqualTo("CONFIRMED");
    }

    /** 왜 시험지가 바뀌었는지가 이력에 남는다. 이게 없으면 나중에 점수 차이를 설명할 수 없다. */
    @Test
    void 확정하면_증분_이력이_남는다() {
        ObjectNode next = LocalEvaluationSetController.merged(pendingSet(), AT);

        JsonNode record = next.withArray("increments").get(0);
        assertThat(record.path("increment").asInt()).isEqualTo(2);
        assertThat(record.path("added").asInt()).isEqualTo(2);
        assertThat(record.path("confirmedAt").asText()).isEqualTo(AT.toString());
        assertThat(record.path("trigger").path("alreadyCovered").asInt()).isEqualTo(50);
    }

    /** 문항마다 어느 증분에서 왔는지가 남아야 증분별 점수 분해가 가능하다. */
    @Test
    void 편입된_문항은_출처_증분을_들고_온다() {
        ObjectNode next = LocalEvaluationSetController.merged(pendingSet(), AT);

        assertThat(next.withArray("questions").get(2).path("origin").path("increment").asInt())
                .isEqualTo(2);
    }

    /** 두 번째 증분이 첫 증분 이력을 덮지 않는다 — 이력은 쌓이는 것이다. */
    @Test
    void 증분_이력은_덮어쓰지_않고_쌓인다() {
        ObjectNode first = LocalEvaluationSetController.merged(pendingSet(), AT);
        ObjectNode withSecond = first.deepCopy();
        withSecond.set("pendingIncrement", set("""
                { "increment": 3, "generatedAt": "2026-09-17T08:00:00Z",
                  "sourceVersionId": "22222222-2222-2222-2222-222222222222",
                  "trigger": { "alreadyCovered": 52, "uncoveredSampled": 10 },
                  "questions": [ { "id": "q04", "expectedExternalDocumentId": "D" } ] }
                """));

        ObjectNode second = LocalEvaluationSetController.merged(
                withSecond, java.time.Instant.parse("2026-09-17T09:00:00Z"));

        assertThat(second.withArray("increments")).hasSize(2);
        assertThat(second.withArray("questions")).hasSize(4);
    }
}
