package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;

/**
 * The record a person inherits when the model gave up.
 *
 * <p>Nothing writes down that a handover happened: the gate that decides it runs inside the
 * graph and records no result of its own. The rule below is read off what it does leave, so it
 * is worth pinning down - a wrong reading either hides an abandoned request or tells someone a
 * finished one was abandoned.
 */
class CodingConsoleHandoverTest {

    private static final Instant FINISHED = Instant.parse("2026-09-03T02:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CodingConsoleService.ResultRow review(String port, String summary, int minute) {
        return new CodingConsoleService.ResultRow(
                "coding.review", port, null, null,
                MAPPER.createObjectNode().put("reportSummary", summary),
                Instant.parse("2026-09-03T01:" + String.format("%02d", minute) + ":00Z"));
    }

    private static CodingConsoleService.ResultRow code(int minute) {
        return new CodingConsoleService.ResultRow(
                "coding.code", "completed", "sha1:" + "a".repeat(40), null, null,
                Instant.parse("2026-09-03T01:" + String.format("%02d", minute) + ":00Z"));
    }

    @Test
    void readsEveryRoundInTheOrderItHappened() {
        // The console hands rows over newest first; a handover is read forwards.
        CodingConsoleContract.Handover handover = CodingConsoleService.handover(FINISHED, List.of(
                review("changes_requested", "세 번째도 기준을 못 채웠습니다", 30),
                code(29),
                review("changes_requested", "두 번째도 부족합니다", 20),
                code(19),
                review("changes_requested", "첫 시도가 기준을 못 채웠습니다", 10),
                code(9)));

        assertThat(handover).isNotNull();
        assertThat(handover.rounds()).isEqualTo(3);
        assertThat(handover.attempts()).extracting(CodingConsoleContract.Attempt::round)
                .containsExactly(1, 2, 3);
        assertThat(handover.attempts().get(0).summary()).isEqualTo("첫 시도가 기준을 못 채웠습니다");
        assertThat(handover.attempts().get(2).summary()).isEqualTo("세 번째도 기준을 못 채웠습니다");
        assertThat(handover.attempts()).allMatch(attempt -> !attempt.accepted());
    }

    @Test
    void saysNothingAboutAJobThatEndedWithAnAcceptedCandidate() {
        // A passed review is followed by the preview, so this ending is not a handover.
        assertThat(CodingConsoleService.handover(FINISHED, List.of(
                review("passed", "요청대로 되었습니다", 10),
                code(9)))).isNull();
    }

    @Test
    void saysNothingWhileTheRequestIsStillRunning() {
        // Mid-loop the newest word is also "changes requested". The difference is that the Job
        // has not finished: another coding round is about to be queued.
        assertThat(CodingConsoleService.handover(null, List.of(
                review("changes_requested", "아직 부족합니다", 10),
                code(9)))).isNull();
    }

    @Test
    void saysNothingAboutARequestThatNeverGotPastThePlan() {
        assertThat(CodingConsoleService.handover(FINISHED, List.of(
                new CodingConsoleService.ResultRow("coding.analyze", "infeasible", null, null,
                        MAPPER.createObjectNode(), FINISHED)))).isNull();
    }
}
