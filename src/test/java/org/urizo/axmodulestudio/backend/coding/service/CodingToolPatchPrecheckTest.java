package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model kept sending patches git refuses: first a hunk header with no line numbers, then
 * headers whose declared counts did not match their own body. git names the defect in stderr,
 * the MCP workspace drops it, and the model is told only that the operation failed - so it
 * sends the same shape again until the turn budget runs out. These tests pin the precheck
 * that names the wrong line instead, because that message is what the model gets to act on.
 * Both checks are internal consistency of the patch text; whether the numbers match the real
 * file stays git's judgement.
 */
class CodingToolPatchPrecheckTest {

    private static final String VALID_PATCH = """
            diff --git a/src/features/site/PublicSite.tsx b/src/features/site/PublicSite.tsx
            --- a/src/features/site/PublicSite.tsx
            +++ b/src/features/site/PublicSite.tsx
            @@ -73,3 +73,3 @@ export default function PublicSite() {
               const title = "before";
            -  return <h1>{title}</h1>;
            +  return <h2>{title}</h2>;
             }
            """;

    @Test
    @DisplayName("A well formed unified diff passes untouched")
    void acceptsWellFormedPatch() {
        assertThatCode(() -> CodingToolService.validatePatchText(VALID_PATCH))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A hunk header may omit a count, which git reads as one line")
    void acceptsHunkHeaderWithoutCounts() {
        assertThatCode(() -> CodingToolService.validatePatchText("""
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1 @@
                -before
                +after
                """))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A patch that survived a CRLF round trip still passes")
    void acceptsCarriageReturnLineEndings() {
        assertThatCode(() -> CodingToolService.validatePatchText(VALID_PATCH.replace("\n", "\r\n")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Several hunks over several files pass when every count is honest")
    void acceptsMultiFilePatch() {
        assertThatCode(() -> CodingToolService.validatePatchText("""
                diff --git a/A.md b/A.md
                --- a/A.md
                +++ b/A.md
                @@ -1,2 +1,2 @@
                -old a
                +new a
                 kept
                @@ -9,1 +9,2 @@
                 tail
                +added
                diff --git a/B.md b/B.md
                --- a/B.md
                +++ b/B.md
                @@ -4,1 +4,1 @@
                -old b
                +new b
                \\ No newline at end of file
                """))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The bare '@@' the model kept sending is refused, and the refusal names its line")
    void refusesHunkHeaderWithoutLineNumbers() {
        assertThatThrownBy(() -> CodingToolService.validatePatchText(
                VALID_PATCH.replace("@@ -73,3 +73,3 @@ export default function PublicSite() {", "@@")))
                .isInstanceOf(CodingToolException.class)
                .satisfies(failure -> assertThat(((CodingToolException) failure).code())
                        .isEqualTo("TOOL_ARGUMENTS_INVALID"))
                // The model can only correct the patch if it is told which line to look at.
                .hasMessageContaining("line 4")
                .hasMessageContaining("@@ -73,7 +73,7 @@");
    }

    @Test
    @DisplayName("Declared counts that do not match the hunk body are refused with both totals")
    void refusesHunkWhoseCountsDisagreeWithItsBody() {
        // The measured failure of Job 7e600583: the header shape is right, but it declares
        // fewer lines than the body carries, so git refuses and the reason is swallowed.
        assertThatThrownBy(() -> CodingToolService.validatePatchText(
                VALID_PATCH.replace("@@ -73,3 +73,3 @@", "@@ -73,7 +73,7 @@")))
                .isInstanceOf(CodingToolException.class)
                .satisfies(failure -> assertThat(((CodingToolException) failure).code())
                        .isEqualTo("TOOL_ARGUMENTS_INVALID"))
                .hasMessageContaining("hunk header at line 4")
                .hasMessageContaining("declares 7 old and 7 new")
                .hasMessageContaining("body has 3 old and 3 new");
    }

    @Test
    @DisplayName("A dishonest count in the last hunk is caught too")
    void refusesCountMismatchInFinalHunk() {
        assertThatThrownBy(() -> CodingToolService.validatePatchText("""
                diff --git a/A.md b/A.md
                --- a/A.md
                +++ b/A.md
                @@ -1,2 +1,2 @@
                -old a
                +new a
                 kept
                @@ -9,3 +9,8 @@
                 tail
                +added
                """))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("hunk header at line 8")
                .hasMessageContaining("declares 3 old and 8 new")
                .hasMessageContaining("body has 1 old and 2 new");
    }

    @Test
    @DisplayName("A patch with no hunk at all is refused")
    void refusesPatchWithoutHunk() {
        assertThatThrownBy(() -> CodingToolService.validatePatchText("""
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                """))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("no hunk");
    }

    @Test
    @DisplayName("A patch missing the '---' and '+++' path lines is refused")
    void refusesPatchWithoutPathLines() {
        assertThatThrownBy(() -> CodingToolService.validatePatchText("""
                diff --git a/README.md b/README.md
                @@ -1,2 +1,2 @@
                -before
                +after
                 kept
                """))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("'--- a/PATH'");
    }

    @Test
    @DisplayName("The old start check still holds")
    void refusesPatchThatDoesNotStartWithDiffGit() {
        assertThatThrownBy(() -> CodingToolService.validatePatchText("""
                --- a/README.md
                +++ b/README.md
                @@ -1,2 +1,2 @@
                -before
                +after
                """))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("diff --git a/PATH b/PATH");
    }
}
