package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class RtkSearchModelViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void processTimeoutAndOutputLimitFallBackWithoutSourceInArgumentsOrPersistentFiles(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var binary = java.nio.file.Files.writeString(temp.resolve("formatter.exe"), "test fixture");
        for (boolean timedOut : new boolean[]{true, false}) {
            Process process = org.mockito.Mockito.mock(Process.class);
            org.mockito.Mockito.when(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(!timedOut);
            org.mockito.Mockito.when(process.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
            org.mockito.Mockito.when(process.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(
                    new byte[RtkSearchModelView.MAX_BYTES + 1]));
            org.mockito.Mockito.when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
            var directory = new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
            var adapter = new RtkSearchModelView(binary.toString()) {
                @Override Process start(ProcessBuilder builder) {
                    assertThat(builder.command()).containsExactly(binary.toString(), "pipe", "--filter", "grep");
                    assertThat(builder.environment()).doesNotContainKeys("OPENAI_API_KEY", "LANGFUSE_SECRET_KEY");
                    directory.set(builder.directory().toPath());
                    return process;
                }
            };
            var view = adapter.render(fixture());
            assertThat(view.content()).isEqualTo(fixture());
            assertThat(view.reason()).isEqualTo("adapter_failure");
            org.mockito.Mockito.verify(process).destroyForcibly();
            assertThat(directory.get()).doesNotExist();
        }
    }

    static String fixture() {
        var source = JSON.createObjectNode().put("query", "needle").put("scope", ".").put("truncated", false);
        var matches = source.putArray("matches");
        for (int i = 0; i < 16; i++) matches.addObject()
                .put("path", "src/" + "long-component-folder/".repeat(10) + "View" + i / 8 + ".tsx")
                .put("line", i + 1).put("column", 7).put("preview", "  한글 needle : \"quoted\" \\ source  ");
        return source.toString();
    }

    private RtkSearchModelView fake(AtomicInteger calls) {
        return new RtkSearchModelView("unused") {
            @Override String run(String input) {
                calls.incrementAndGet();
                StringBuilder output = new StringBuilder();
                String previous = null;
                for (String row : input.split("\n")) {
                    String[] parts = row.split(":", 3);
                    if (!parts[0].equals(previous)) output.append("[file] ").append(parts[0]).append(" (8):\n");
                    output.append("  ").append(parts[1]).append(": ").append(parts[2]).append('\n');
                    previous = parts[0];
                }
                return output.toString();
            }
        };
    }

    @Test void validatesAllInformationAndCompressesOnlyTheModelCopy() throws Exception {
        var count = new AtomicInteger();
        var adapter = fake(count);
        String raw = fixture();
        var assistant = JSON.createObjectNode().put("role", "assistant");
        assistant.putArray("toolCalls").addObject().put("toolCallId", "call").put("name", "search_code");
        var tool = JSON.createObjectNode().put("role", "tool").put("toolCallId", "call").put("content", raw);
        tool.putObject("result").put("digest", "unchanged");
        List<JsonNode> history = new ArrayList<>(List.of(assistant, tool));
        var cache = new LinkedHashMap<String, RtkSearchModelView.View>();
        var first = adapter.apply(history, cache);
        var second = adapter.apply(history, cache);
        assertThat(first.messages()).isEqualTo(second.messages());
        assertThat(first.selected()).isEqualTo(1);
        assertThat(first.modelBytes()).isLessThan(first.originalBytes());
        assertThat(count.get()).isEqualTo(1);
        assertThat(tool.path("content").asText()).isEqualTo(raw);
        assertThat(first.messages().get(1).path("result")).isEqualTo(tool.path("result"));
        // Folded originals cannot be reconstructed by the memoized model view.
        tool.put("content", CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        assertThat(adapter.apply(history, cache).messages().get(1).path("content").asText())
                .isEqualTo(CodingHandlerStageService.FOLDED_TOOL_CONTENT);
    }

    @Test void rejectsDuplicateUnknownFieldsAndInvalidCoordinatesWithoutInvokingRtk() throws Exception {
        var calls = new AtomicInteger();
        var adapter = fake(calls);
        String raw = fixture();
        var unknown = JSON.readTree(raw).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("extra", "keep");
        var invalid = JSON.readTree(raw);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("matches").get(0)).put("line", 0);
        for (String input : List.of("{}", raw.replace("\"query\":", "\"query\":\"other\",\"query\":"),
                unknown.toString(), invalid.toString(), raw + " trailing")) {
            assertThat(adapter.render(input).content()).isEqualTo(input);
        }
        assertThat(calls.get()).isZero();
    }

    @Test void missingExecutableLossAndOutputExpansionReturnOriginal() {
        String raw = fixture();
        assertThat(new RtkSearchModelView("").render(raw).content()).isEqualTo(raw);
        for (String output : List.of("lost rows", "x".repeat(RtkSearchModelView.MAX_BYTES))) {
            var adapter = new RtkSearchModelView("unused") { @Override String run(String input) { return output; } };
            assertThat(adapter.render(raw).content()).isEqualTo(raw);
        }
        var failure = new RtkSearchModelView("unused") {
            @Override String run(String input) throws IOException { throw new IOException("private path"); }
        };
        assertThat(failure.render(raw).reason()).isEqualTo("adapter_failure");
    }

    @Test void unrelatedAndUnmatchedToolMessagesNeverInvokeTheFormatter() {
        var calls = new AtomicInteger(); var adapter = fake(calls);
        var tool = JSON.createObjectNode().put("role", "tool").put("toolCallId", "unknown").put("content", fixture());
        assertThat(adapter.apply(List.of(tool), new LinkedHashMap<>()).messages()).containsExactly(tool);
        assertThat(calls.get()).isZero();
    }

    @Test void fixedLocalRtkBinaryWorksThroughTheProductionProcessBoundary() {
        String path = System.getProperty("axms.test.rtkExecutable");
        Assumptions.assumeTrue(path != null, "Explicit local RTK binary required; no download in tests");
        var view = new RtkSearchModelView(path).render(fixture());
        assertThat(view.reason()).isEqualTo("selected");
        assertThat(view.modelBytes()).isLessThan(view.originalBytes());
    }
}
