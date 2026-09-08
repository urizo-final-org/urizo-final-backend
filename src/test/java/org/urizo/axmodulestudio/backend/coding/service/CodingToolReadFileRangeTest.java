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
