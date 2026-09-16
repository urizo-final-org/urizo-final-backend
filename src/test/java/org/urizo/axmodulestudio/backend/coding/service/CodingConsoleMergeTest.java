package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;

/**
 * What the DEPLOY gate says about the merge it is waiting on.
 *
 * <p>The gate opens twice by design - once while the pull request is still open, once after it
 * is in {@code dev} - and until now the screen showed the same panel both times. Someone
 * pressed approve, got the gate back, and had nothing to tell them whether that was the merge
 * failing, the runner failing, or the design working as intended. The verdict was recorded the
 * whole time; nobody read it back out.
 *
 * <p>Two rows answer the one question, which is the part worth pinning down: the verdict and
 * the merge sha come from {@code coding.dev_merge_check}, while the PR number and URL are on
 * {@code coding.pr_complete}. Reading either alone gives a half-answer.
 */
class CodingConsoleMergeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CANDIDATE = "sha1:" + "a".repeat(40);
    private static final String MERGE_SHA = "sha1:" + "b".repeat(40);
    private static final String PR_URL =
            "https://github.com/urizo-final-org/urizo-final-frontend/pull/93";

    private static CodingConsoleService.ResultRow check(String status, int minute, String... extra) {
        ObjectNode payload = MAPPER.createObjectNode()
                .put("repository", "frontend")
                .put("base", "dev")
                .put("head", "system/llmops-8edb08e5")
                .put("prNumber", 93)
                .put("candidateSha", CANDIDATE)
                .put("status", status);
        for (int index = 0; index < extra.length; index += 2) {
            payload.put(extra[index], extra[index + 1]);
        }
        return new CodingConsoleService.ResultRow(
                "coding.dev_merge_check", status.toLowerCase(), CANDIDATE, null, payload,
                Instant.parse("2026-09-16T01:" + String.format("%02d", minute) + ":00Z"));
    }

    private static CodingConsoleService.ResultRow pullRequest() {
        return new CodingConsoleService.ResultRow(
                "coding.pr_complete", "completed", CANDIDATE, null,
                MAPPER.createObjectNode()
                        .put("repository", "frontend")
                        .put("prNumber", 93)
                        .put("prUrl", PR_URL)
                        .put("state", "OPEN"),
                Instant.parse("2026-09-16T00:59:44Z"));
    }

    /** The console hands rows over newest first. */
    private static List<CodingConsoleService.ResultRow> newestFirst(
            CodingConsoleService.ResultRow... rows) {
        return List.of(rows);
    }

    @Test
    void saysTheWorkIsNotInDevYetAndWhereToGoAboutIt() {
        CodingConsoleContract.Merge merge = CodingConsoleService.merge(
                newestFirst(check("NOT_MERGED", 30), pullRequest()));

        assertThat(merge).isNotNull();
        assertThat(merge.status()).isEqualTo("NOT_MERGED");
        // The whole point of the panel: the person has to go and merge it, so they are given
        // the number and the link rather than being told to go and find it.
        assertThat(merge.prNumber()).isEqualTo(93);
        assertThat(merge.prUrl()).isEqualTo(PR_URL);
        assertThat(merge.mergeSha()).isNull();
        assertThat(merge.checkedAt()).isEqualTo(Instant.parse("2026-09-16T01:30:00Z"));
    }

    @Test
    void confirmsTheMergeWithTheShaThatProvesIt() {
        CodingConsoleContract.Merge merge = CodingConsoleService.merge(
                newestFirst(check("MERGED", 45, "mergeSha", MERGE_SHA), pullRequest()));

        assertThat(merge.status()).isEqualTo("MERGED");
        assertThat(merge.mergeSha()).isEqualTo(MERGE_SHA);
        assertThat(merge.reason()).isNull();
    }

    @Test
    void carriesTheReasonWhenTheCheckItselfWasBlocked() {
        CodingConsoleContract.Merge merge = CodingConsoleService.merge(
                newestFirst(check("BLOCKED", 45, "reason", "PR 이 닫힌 뒤 병합되지 않았습니다."),
                        pullRequest()));

        assertThat(merge.status()).isEqualTo("BLOCKED");
        // Blocked is the only verdict a person cannot act on from the gate, so the reason is
        // the entire value of the field.
        assertThat(merge.reason()).isEqualTo("PR 이 닫힌 뒤 병합되지 않았습니다.");
        assertThat(merge.mergeSha()).isNull();
    }

    @Test
    void readsTheSecondPressAndNotTheFirst() {
        // The gate opened twice: not merged at 01:30, merged at 01:45. Rows arrive newest
        // first, and reading the older row would tell the person to merge a merged PR.
        CodingConsoleContract.Merge merge = CodingConsoleService.merge(
                newestFirst(check("MERGED", 45, "mergeSha", MERGE_SHA),
                        check("NOT_MERGED", 30), pullRequest()));

        assertThat(merge.status()).isEqualTo("MERGED");
        assertThat(merge.mergeSha()).isEqualTo(MERGE_SHA);
    }

    @Test
    void staysSilentBeforeTheCheckHasEverRun() {
        // Every Job before its first DEPLOY approval. Saying "not merged" here would be a
        // statement about a pull request that does not exist.
        assertThat(CodingConsoleService.merge(newestFirst(pullRequest()))).isNull();
        assertThat(CodingConsoleService.merge(List.of())).isNull();
    }

    @Test
    void reportsTheVerdictEvenWithoutTheReceiptThatHoldsTheLink() {
        // The URL lives on one row and the verdict on another. A missing receipt costs the
        // link, not the answer.
        CodingConsoleContract.Merge merge = CodingConsoleService.merge(
                newestFirst(check("NOT_MERGED", 30)));

        assertThat(merge.status()).isEqualTo("NOT_MERGED");
        assertThat(merge.prNumber()).isEqualTo(93);
        assertThat(merge.prUrl()).isNull();
    }
}
