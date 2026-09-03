package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model kept sending a patch whose hunk header had no line numbers. git refused it, the MCP
 * workspace dropped git's reason, and the model was told only that the operation failed - so it
 * sent the same shape again until the turn budget ran out. These tests pin the precheck that
 * names the wrong line instead, because that message is what the model gets to act on.
 */
class CodingToolPatchPrecheckTest {

    private static final String VALID_PATCH = """
            diff --git a/src/features/site/PublicSite.tsx b/src/features/site/PublicSite.tsx
            --- a/src/features/site/PublicSite.tsx
            +++ b/src/features/site/PublicSite.tsx
            @@ -73,7 +73,7 @@ export default function PublicSite() {
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
    @DisplayName("A hunk header may omit the counts, as git reads '@@ -73 +73 @@' as one line")
    void acceptsHunkHeaderWithoutCounts() {
        assertThatCode(() -> CodingToolService.validatePatchText(
                VALID_PATCH.replace("@@ -73,7 +73,7 @@", "@@ -73 +73 @@")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A patch that survived a CRLF round trip still passes")
    void acceptsCarriageReturnLineEndings() {
        assertThatCode(() -> CodingToolService.validatePatchText(VALID_PATCH.replace("\n", "\r\n")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The bare '@@' the model kept sending is refused, and the refusal names its line")
    void refusesHunkHeaderWithoutLineNumbers() {
        assertThatThrownBy(() -> CodingToolService.validatePatchText(
                VALID_PATCH.replace("@@ -73,7 +73,7 @@ export default function PublicSite() {", "@@")))
                .isInstanceOf(CodingToolException.class)
                .satisfies(failure -> assertThat(((CodingToolException) failure).code())
                        .isEqualTo("TOOL_ARGUMENTS_INVALID"))
                // The model can only correct the patch if it is told which line to look at.
                .hasMessageContaining("line 4")
                .hasMessageContaining("@@ -73,7 +73,7 @@");
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
