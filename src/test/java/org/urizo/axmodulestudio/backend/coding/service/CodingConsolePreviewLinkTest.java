package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;

/**
 * The approval screen's answer to "is there a preview to open".
 *
 * <p>This is written from two incidents rather than from a requirement. A Job whose BUILD and
 * PREVIEW_UP both failed sat in WAITING_APPROVAL with the link offered, because the only thing
 * consulted was that the preview stage had recorded a result. Opening it showed the previous
 * request's preview: a working site, and not the one under review.
 *
 * <p>The fix then overcorrected. Job 9b55bb27 had its TEST fail while PREVIEW_UP succeeded -
 * the preview was genuinely up - and the link was withheld anyway, because one failed row
 * answered both questions. The person approving lost the only thing they could judge and kept
 * the button that mattered. The two facts are separate fields now, and these tests hold them
 * apart.
 */
class CodingConsolePreviewLinkTest {

    private static CodingConsoleService.RunnerRow row(String kind, String status) {
        return new CodingConsoleService.RunnerRow(kind, status, null, null);
    }

    @Test
    void offersTheLinkOnlyOnceThePreviewIsActuallyUp() {
        CodingConsoleContract.PreviewLink link = CodingConsoleService.previewLink(
                List.of(row("BUILD", "SUCCEEDED"), row("PREVIEW_UP", "SUCCEEDED")), true);

        assertThat(link.ready()).isTrue();
        assertThat(link.url()).isNotNull();
        assertThat(link.checkFailure()).isNull();
        assertThat(link.blocked()).isNull();
    }

    @Test
    void withholdsTheLinkWhileTheStackIsStillBeingRaised() {
        CodingConsoleContract.PreviewLink link = CodingConsoleService.previewLink(
                List.of(row("BUILD", "SUCCEEDED"), row("PREVIEW_UP", "RUNNING")), true);

        // Not ready and not blocked: nothing has gone wrong, it is simply not there yet.
        assertThat(link.ready()).isFalse();
        assertThat(link.url()).isNull();
        assertThat(link.checkFailure()).isNull();
        assertThat(link.blocked()).isNull();
    }

    @Test
    void stillOffersThePreviewThatIsUpWhenACheckFailed() {
        // Job 9b55bb27: TEST failed, PREVIEW_UP succeeded, and the preview was genuinely up.
        // Both facts are true at once, so both are reported. Withholding the link here is the
        // thing this field exists to stop.
        CodingConsoleContract.PreviewLink link = CodingConsoleService.previewLink(
                List.of(row("BUILD", "SUCCEEDED"), row("TEST", "FAILED"),
                        row("PREVIEW_UP", "SUCCEEDED")), true);

        assertThat(link.ready()).isTrue();
        assertThat(link.url()).isNotNull();
        assertThat(link.checkFailure()).contains("검사를 통과하지 못했습니다");
        assertThat(link.blocked()).isNull();
    }

    @Test
    void reportsBothTheFailedCheckAndTheMissingPreview() {
        // A build that failed keeps the preview down as well, and the person is owed both:
        // what went wrong, and that there is nothing to open.
        CodingConsoleContract.PreviewLink link = CodingConsoleService.previewLink(
                List.of(row("BUILD", "FAILED")), true);

        assertThat(link.ready()).isFalse();
        assertThat(link.url()).isNull();
        assertThat(link.checkFailure()).contains("미리보기를 준비하지 못했습니다");
        assertThat(link.blocked()).isNotNull();
    }

    @Test
    void blamesTheCheckOnlyWhenACheckIsWhatFailed() {
        // PREVIEW_UP is Docker work, not a check. Reporting it as a failed check would make the
        // approval gate refuse a result whose checks actually passed.
        CodingConsoleContract.PreviewLink link = CodingConsoleService.previewLink(
                List.of(row("BUILD", "SUCCEEDED"), row("TEST", "SUCCEEDED"),
                        row("PREVIEW_UP", "FAILED")), true);

        assertThat(link.ready()).isFalse();
        assertThat(link.checkFailure()).isNull();
        assertThat(link.blocked()).contains("미리보기를 띄우지 못했습니다");
    }

    @Test
    void tellsAGeneralAdministratorNothingThatNamesAFileOrASymbol() {
        // Approval 2 exists so that someone who cannot read code can still judge the result.
        // The runner's own words name paths and compiler codes; they belong to the other reader.
        for (String kind : List.of("BUILD", "TEST", "PREVIEW_UP")) {
            CodingConsoleContract.PreviewLink link = CodingConsoleService.previewLink(
                    List.of(row(kind, "FAILED")), true);

            assertThat(link.blocked()).doesNotContain("/", ".ts", ".java", "TS", "error");
            if (link.checkFailure() != null) {
                assertThat(link.checkFailure()).doesNotContain("/", ".ts", ".java", "TS", "error");
            }
        }
    }

    @Test
    void keepsTheOldAnswerForAJobQueuedBeforeAnyOfThisWasRecorded() {
        // Rows carry the workspace only for Jobs the current intake created. No evidence is
        // not evidence of failure, so an older Job reads as it always did.
        CodingConsoleContract.PreviewLink link =
                CodingConsoleService.previewLink(List.of(), true);

        assertThat(link.ready()).isTrue();
        assertThat(link.url()).isNotNull();
        assertThat(link.checkFailure()).isNull();
        assertThat(link.blocked()).isNull();
    }
}
