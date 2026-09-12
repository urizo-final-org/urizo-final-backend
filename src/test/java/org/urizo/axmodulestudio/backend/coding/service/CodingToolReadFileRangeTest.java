package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * read_file grew optional startLine/endLine because two measured Jobs (06b2b251,
 * bcb6806b) died right after a 42,695-character file entered the conversation. The
 * server slices; the model only names the range. These tests pin the slicing arithmetic,
 * the refusals a model is expected to correct from, and the byte-for-byte round trip
 * that keeps oldText copied out of a slice matching the real file.
 */
class CodingToolReadFileRangeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode args(Object... pairs) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", "src/App.tsx");
        for (int index = 0; index < pairs.length; index += 2) {
            node.put((String) pairs[index], (Integer) pairs[index + 1]);
        }
        return node;
    }

    @Test
    @DisplayName("A whole-file read under the bound returns the content untouched")
    void smallWholeReadIsUntouched() {
        String content = "line one\nline two\n";
        assertThat(CodingToolService.readFileView(args(), content)).isSameAs(content);
    }

    @Test
    @DisplayName("A whole-file read over the bound is refused with the numbers to retry with")
    void oversizedWholeReadIsRefusedWithLineCount() {
        String content = ("x".repeat(80) + "\n")
                .repeat(1 + CodingToolService.MAX_READ_FILE_CONTENT_CHARACTERS / 81);
        assertThatThrownBy(() -> CodingToolService.readFileView(args(), content))
                .isInstanceOf(CodingToolException.class)
                .hasFieldOrPropertyWithValue("code", "TOOL_ARGUMENTS_INVALID")
                .hasMessageContaining("startLine")
                .hasMessageContaining(String.valueOf(content.split("\n", -1).length));
    }

    /** 300 filler lines with declarations at lines 3, 150 and 298 - over the bound as a whole. */
    private static String sourceWithDeclarations() {
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 300; line++) {
            content.append(switch (line) {
                case 3 -> "function alpha(a: number) {";
                case 150 -> "export const BETA = { name: 'x' }";
                case 298 -> "export default function Gamma() {";
                default -> "  " + "x".repeat(84);
            }).append('\n');
        }
        return content.toString();
    }

    @Test
    @DisplayName("A refused whole read of a source file lists its declarations with line numbers")
    void oversizedWholeReadCarriesAnOutline() {
        assertThatThrownBy(() -> CodingToolService.readFileView(args(), sourceWithDeclarations()))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("Outline (line: declaration):")
                .hasMessageContaining("\n3: function alpha(a: number) {")
                .hasMessageContaining("\n150: export const BETA = { name: 'x' }")
                .hasMessageContaining("\n298: export default function Gamma() {")
                // Indented body lines are not declarations.
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("\n4: "));
    }

    @Test
    @DisplayName("A file that is not source code gets the plain refusal, without an outline")
    void oversizedWholeReadOfANonSourceFileHasNoOutline() {
        ObjectNode readme = MAPPER.createObjectNode();
        readme.put("path", "README.md");
        assertThatThrownBy(() -> CodingToolService.readFileView(readme, sourceWithDeclarations()))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("startLine")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("Outline"));
    }

    @Test
    @DisplayName("The outline stops at its cap and says how many declarations it left out")
    void outlineIsCappedAndCountsTheRest() {
        String[] lines = new String[70];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = "const v" + index + " = " + index + "\r";
        }
        String outline = CodingToolService.fileOutline("src/many.ts", lines);
        assertThat(outline)
                .contains("\n1: const v0 = 0")
                .contains("\n60: const v59 = 59")
                .doesNotContain("\n61: ")
                .doesNotContain("\r")
                .endsWith("... and 10 more");
        assertThat(CodingToolService.fileOutline("src/App.java", new String[] {
            "package a;", "", "public final class App {", "    private int count;",
            "    public void run() {", "        run();", "    }", "}"}))
                .isEqualTo(" Outline (line: declaration):\n3: public final class App {"
                        + "\n4:     private int count;\n5:     public void run() {");
    }

    @Test
    @DisplayName("A ranged read returns one note line and then exactly the asked lines")
    void rangedReadReturnsNoteAndSlice() {
        String view = CodingToolService.readFileView(
                args("startLine", 2, "endLine", 3), "alpha\nbeta\ngamma\ndelta\n");
        assertThat(view).isEqualTo(
                "[read_file: lines 2-3 of 5; this note line is not part of the file]\n"
                        + "beta\ngamma");
    }

    @Test
    @DisplayName("CRLF content round-trips byte for byte, so oldText copied out still matches")
    void crlfSliceKeepsBytes() {
        String view = CodingToolService.readFileView(
                args("startLine", 2, "endLine", 3), "alpha\r\nbeta\r\ngamma\r\ndelta\r\n");
        assertThat(view).endsWith("\nbeta\r\ngamma\r");
    }

    @Test
    @DisplayName("startLine past the end of the file is refused with the real line count")
    void startPastEndIsRefused() {
        assertThatThrownBy(() -> CodingToolService.readFileView(
                args("startLine", 9), "alpha\nbeta\n"))
                .isInstanceOf(CodingToolException.class)
                .hasFieldOrPropertyWithValue("code", "TOOL_ARGUMENTS_INVALID")
                .hasMessageContaining("only 3 lines");
    }

    @Test
    @DisplayName("A range that still exceeds the bound is refused, not silently truncated")
    void oversizedRangeIsRefused() {
        String content = "y".repeat(CodingToolService.MAX_READ_FILE_CONTENT_CHARACTERS + 1);
        assertThatThrownBy(() -> CodingToolService.readFileView(
                args("startLine", 1, "endLine", 1), content))
                .isInstanceOf(CodingToolException.class)
                .hasMessageContaining("Narrow the range");
    }

    @Test
    @DisplayName("endLine beyond the end clamps instead of failing")
    void endBeyondEndClamps() {
        String view = CodingToolService.readFileView(
                args("startLine", 2, "endLine", 400), "alpha\nbeta\ngamma");
        assertThat(view).endsWith("\nbeta\ngamma");
    }

    @Test
    @DisplayName("Argument validation accepts the optional range and names each bad shape")
    void argumentValidationCoversTheRange() {
        CodingToolService.validateToolArguments("read_file", args());
        CodingToolService.validateToolArguments("read_file", args("startLine", 5));
        CodingToolService.validateToolArguments(
                "read_file", args("startLine", 5, "endLine", 9));

        assertThatThrownBy(() -> CodingToolService.validateToolArguments(
                "read_file", args("startLine", 0)))
                .hasMessageContaining("1 or greater");
        assertThatThrownBy(() -> CodingToolService.validateToolArguments(
                "read_file", args("startLine", 5, "endLine", 4)))
                .hasMessageContaining("before startLine");
        ObjectNode unknown = args();
        unknown.put("lines", 3);
        assertThatThrownBy(() -> CodingToolService.validateToolArguments(
                "read_file", unknown))
                .hasMessageContaining("read_file arguments are invalid");
    }
}
