package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * apply_patch accepts two shapes - a diff, or the exact text to replace - and JSON Schema
 * cannot express "exactly one of these two sets" in the subset the provider gateway allows.
 * So nothing is required in the schema and the choice is enforced here instead. That makes
 * these refusal messages part of the contract rather than diagnostics: the stage replays
 * each one to the model verbatim, and it is the only account the model gets of what it did
 * wrong. A message that merely says the arguments are invalid costs a turn and teaches
 * nothing, which is the failure this whole change exists to remove.
 */
class CodingToolReplacementArgumentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode arguments() {
        return MAPPER.createObjectNode();
    }

    @Test
    @DisplayName("The replacement form is accepted, and an empty newText means deletion")
    void acceptsTheReplacementForm() {
        ObjectNode replace = arguments()
                .put("path", "src/features/site/PublicSite.tsx")
                .put("oldText", "사업 소개 보기")
                .put("newText", "장차윤 ㅋㅋ");
        ObjectNode delete = arguments()
                .put("path", "src/features/site/PublicSite.tsx")
                .put("oldText", "  <p>지울 줄</p>\n")
                .put("newText", "");

        assertThatCode(() -> CodingToolService.validateToolArguments("apply_patch", replace))
                .doesNotThrowAnyException();
        assertThatCode(() -> CodingToolService.validateToolArguments("apply_patch", delete))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The diff form still works exactly as it did")
    void keepsAcceptingTheDiffForm() {
        ObjectNode patch = arguments().put("patch", """
                diff --git a/p.txt b/p.txt
                --- a/p.txt
                +++ b/p.txt
                @@ -1,1 +1,1 @@
                -old
                +new
                """);

        assertThatCode(() -> CodingToolService.validateToolArguments("apply_patch", patch))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A half-finished replacement is named field by field, not called invalid")
    void namesTheFieldsThatWereSent() {
        ObjectNode half = arguments().put("path", "p.tsx").put("oldText", "a");

        assertThatThrownBy(() -> CodingToolService.validateToolArguments("apply_patch", half))
                .hasMessageContaining("all three of 'path', 'oldText' and 'newText'")
                .hasMessageContaining("This call sent oldText, path");
    }

    @Test
    @DisplayName("Mixing a diff with the replacement fields is refused")
    void refusesAMixtureOfBothForms() {
        ObjectNode mixed = arguments()
                .put("patch", "diff --git a/p b/p\n--- a/p\n+++ b/p\n@@ -1 +1 @@\n-a\n+b\n")
                .put("path", "p").put("oldText", "a").put("newText", "b");

        assertThatThrownBy(() -> CodingToolService.validateToolArguments("apply_patch", mixed))
                .hasMessageContaining("either 'patch' alone");
    }

    @Test
    @DisplayName("An empty call says which two shapes exist instead of only refusing")
    void explainsBothShapesWhenNothingWasSent() {
        assertThatThrownBy(() ->
                CodingToolService.validateToolArguments("apply_patch", arguments()))
                .hasMessageContaining("This call sent no arguments");
    }

    @Test
    @DisplayName("An oldText that changes nothing is refused before the file is read")
    void refusesAReplacementThatChangesNothing() {
        ObjectNode same = arguments()
                .put("path", "p.tsx").put("oldText", "same").put("newText", "same");

        assertThatThrownBy(() -> CodingToolService.validateToolArguments("apply_patch", same))
                .hasMessageContaining("identical");
    }

    @Test
    @DisplayName("Each side is bounded, because the gateway caps a whole call at 32,768 characters")
    void boundsEachSideOfTheReplacement() {
        ObjectNode tooLong = arguments()
                .put("path", "p.tsx").put("oldText", "x".repeat(8_001)).put("newText", "y");
        ObjectNode empty = arguments()
                .put("path", "p.tsx").put("oldText", "").put("newText", "y");

        assertThatThrownBy(() -> CodingToolService.validateToolArguments("apply_patch", tooLong))
                .hasMessageContaining("at most 8000 characters");
        assertThatThrownBy(() -> CodingToolService.validateToolArguments("apply_patch", empty))
                .hasMessageContaining("may not be empty");
    }

    @Test
    @DisplayName("The path is still held to the workspace path policy")
    void keepsThePathPolicy() {
        ObjectNode escaping = arguments()
                .put("path", "../secrets/.env").put("oldText", "a").put("newText", "b");

        assertThatThrownBy(() -> CodingToolService.validateToolArguments("apply_patch", escaping))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("workspace path scope");
    }
}
