package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A hunk header carries three different kinds of thing, and only one of them belongs to the
 * model. The body is its intent. The start line is a claim about the file that only git can
 * check against the real bytes. The counts are arithmetic over the body - they hold nothing
 * the body does not already hold - so the server fills them in and never asks the model to
 * add up. Job e0fb866f spent fifteen straight refusals on that arithmetic, over a context
 * line 1,387 characters long, and gave up with the file unchanged.
 *
 * <p>Rewriting the counts does not weaken anything: git still matches every context line
 * against the file, so a body that says the wrong thing is refused whatever the header claims.
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
    @DisplayName("A patch whose counts are already honest is handed on untouched")
    void leavesAnHonestPatchExactlyAsItIs() {
        assertThat(CodingToolService.normalizePatchText(VALID_PATCH)).isEqualTo(VALID_PATCH);
    }

    @Test
    @DisplayName("Counts that disagree with the body are corrected, and only the counts")
    void rewritesOnlyTheCounts() {
        // The measured shape of Job e0fb866f: the header claims more lines than it carries.
        String dishonest = VALID_PATCH.replace("@@ -73,3 +73,3 @@", "@@ -73,7 +73,7 @@");

        String normalized = CodingToolService.normalizePatchText(dishonest);

        assertThat(normalized).isEqualTo(VALID_PATCH);
        // The start line is a claim about the file, so it survives untouched for git to judge.
        assertThat(normalized).contains("@@ -73,3 +73,3 @@ export default function PublicSite() {");
        // Not one character of the body moved.
        assertThat(normalized.lines().filter(line -> line.startsWith("-") || line.startsWith("+"))
                .filter(line -> !line.startsWith("---") && !line.startsWith("+++")).toList())
                .containsExactly("-  return <h1>{title}</h1>;", "+  return <h2>{title}</h2>;");
    }

    @Test
    @DisplayName("A missing count is filled in rather than read as one line")
    void fillsInAnAbsentCount() {
        String noCounts = VALID_PATCH.replace("@@ -73,3 +73,3 @@", "@@ -73 +73 @@");

        assertThat(CodingToolService.normalizePatchText(noCounts)).isEqualTo(VALID_PATCH);
    }

    @Test
    @DisplayName("Every hunk of every file is counted on its own")
    void rewritesEachHunkOfAMultiFilePatch() {
        String normalized = CodingToolService.normalizePatchText("""
                diff --git a/A.md b/A.md
                --- a/A.md
                +++ b/A.md
                @@ -1,9 +1,9 @@
                -old a
                +new a
                 kept
                @@ -9,4 +9,4 @@
                 tail
                +added
                diff --git a/B.md b/B.md
                --- a/B.md
                +++ b/B.md
                @@ -4,6 +4,6 @@
                -old b
                +new b
                \\ No newline at end of file
                """);

        assertThat(normalized.lines().filter(line -> line.startsWith("@@")).toList())
                .containsExactly("@@ -1,2 +1,2 @@", "@@ -9,1 +9,2 @@", "@@ -4,1 +4,1 @@");
    }

    @Test
    @DisplayName("A patch that survived a CRLF round trip keeps its line endings")
    void keepsCarriageReturnLineEndings() {
        String crlf = VALID_PATCH.replace("@@ -73,3 +73,3 @@", "@@ -73,7 +73,7 @@")
                .replace("\n", "\r\n");

        assertThat(CodingToolService.normalizePatchText(crlf))
                .isEqualTo(VALID_PATCH.replace("\n", "\r\n"));
    }

    @Test
    @DisplayName("The bare '@@' the model kept sending is refused, and the refusal names its line")
    void refusesHunkHeaderWithoutLineNumbers() {
        // Shape, unlike arithmetic, is not something the server can infer: a header with no
        // numbers at all says nothing about where the change goes.
        assertThatThrownBy(() -> CodingToolService.normalizePatchText(
                VALID_PATCH.replace("@@ -73,3 +73,3 @@ export default function PublicSite() {", "@@")))
                .isInstanceOf(CodingToolException.class)
                .satisfies(failure -> assertThat(((CodingToolException) failure).code())
                        .isEqualTo("TOOL_ARGUMENTS_INVALID"))
                .hasMessageContaining("line 4")
                .hasMessageContaining("@@ -73,7 +73,7 @@");
    }

    @Test
    @DisplayName("A patch with no hunk at all is refused")
    void refusesPatchWithoutHunk() {
        assertThatThrownBy(() -> CodingToolService.normalizePatchText("""
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
        assertThatThrownBy(() -> CodingToolService.normalizePatchText("""
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
        assertThatThrownBy(() -> CodingToolService.normalizePatchText("""
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
