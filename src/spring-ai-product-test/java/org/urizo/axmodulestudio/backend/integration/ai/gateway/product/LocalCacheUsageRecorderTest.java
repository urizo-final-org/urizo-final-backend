package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;

class LocalCacheUsageRecorderTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    static ChatResponse response(Integer input, Integer cached) {
        var nativeUsage = new OpenAiApi.Usage(12, input, input == null ? null : input + 12,
                new OpenAiApi.Usage.PromptTokensDetails(null, cached), null);
        return new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(input, 12, null, nativeUsage)).build());
    }

    @Test
    void recordsActualCachedTokensWithoutDoubleCountingAndInheritsTurnIdentifiers() throws Exception {
        Path file = temp.resolve("usage.jsonl");
        var recorder = new LocalCacheUsageRecorder(true, file.toString(), true);
        UUID job = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        try (var outer = ModelObservationScope.open(job, UUID.randomUUID(), UUID.randomUUID(), "code");
             var inner = ModelObservationScope.openTurn(turn, 2)) {
            recorder.record(ModelProvider.OPENAI, "test-model", response(2000, 1536), NOW, NOW.plusMillis(42), true);
        }
        JsonNode row = JSON.readTree(Files.readString(file));
        assertThat(row.get("inputTokens").intValue()).isEqualTo(2000);
        assertThat(row.get("cachedInputTokens").intValue()).isEqualTo(1536);
        assertThat(row.get("uncachedInputTokens").intValue()).isEqualTo(464);
        assertThat(row.get("cacheWriteTokens").isNull()).isTrue();
        assertThat(row.get("jobId").textValue()).isEqualTo(job.toString());
        assertThat(row.get("turnId").textValue()).isEqualTo(turn.toString());
        assertThat(row.get("executionAttempt").intValue()).isEqualTo(2);
        assertThat(row.get("latencyMs").intValue()).isEqualTo(42);
        assertThat(row.get("searchGroupingEnabled").booleanValue()).isFalse();
    }

    @Test
    void distinguishesZeroMissingAndInvalidCacheDetails() throws Exception {
        Path file = temp.resolve("usage.jsonl");
        var recorder = new LocalCacheUsageRecorder(true, file.toString(), false);
        for (Integer cached : new Integer[]{0, null, -1, 2001}) {
            recorder.record(ModelProvider.OPENAI, "test-model", response(2000, cached), NOW, NOW, true);
        }
        var rows = Files.readAllLines(file);
        assertThat(JSON.readTree(rows.get(0)).get("cachedInputTokens").intValue()).isZero();
        assertThat(JSON.readTree(rows.get(0)).get("uncachedInputTokens").intValue()).isEqualTo(2000);
        for (String line : rows.subList(1, rows.size())) {
            assertThat(JSON.readTree(line).get("cachedInputTokens").isNull()).isTrue();
            assertThat(JSON.readTree(line).get("uncachedInputTokens").isNull()).isTrue();
        }
    }

    @Test
    void missingUsageAndOtherProviderCacheStayUnknownAndNativeObjectsAreNeverSerialized() throws Exception {
        Path file = temp.resolve("usage.jsonl");
        var recorder = new LocalCacheUsageRecorder(true, file.toString(), false);
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(-1);
        when(usage.getNativeUsage()).thenReturn(java.util.Map.of("secret", "DO_NOT_RECORD_NATIVE"));
        recorder.record(ModelProvider.OPENAI, "test-model", new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(usage).build()), NOW, NOW, true);
        recorder.record(ModelProvider.OPENAI, "test-model", null, NOW, NOW, false);
        recorder.record(ModelProvider.ANTHROPIC, "test-model", response(2000, 1000), NOW, NOW, true);
        var rows = Files.readAllLines(file);
        assertThat(JSON.readTree(rows.get(0)).get("inputTokens").isNull()).isTrue();
        assertThat(JSON.readTree(rows.get(1)).get("usagePresent").booleanValue()).isFalse();
        assertThat(JSON.readTree(rows.get(1)).get("outcome").textValue()).isEqualTo("FAILED");
        assertThat(JSON.readTree(rows.get(2)).get("cachedInputTokens").isNull()).isTrue();
        assertThat(Files.readString(file)).doesNotContain("DO_NOT_RECORD_NATIVE", "secret");
    }

    @Test
    void sdkPlaceholdersDoNotTurnMissingProviderUsageIntoMeasuredZeros() throws Exception {
        Path file = temp.resolve("usage.jsonl");
        var recorder = new LocalCacheUsageRecorder(true, file.toString(), false);
        recorder.record(ModelProvider.OPENAI, "test-model", new ChatResponse(List.of()), NOW, NOW, true);
        recorder.record(ModelProvider.OPENAI, "test-model", response(null, 0), NOW, NOW, true);
        var lines = Files.readAllLines(file);
        JsonNode empty = JSON.readTree(lines.get(0));
        assertThat(empty.path("usagePresent").booleanValue()).isFalse();
        assertThat(empty.path("inputTokens").isNull()).isTrue();
        JsonNode missing = JSON.readTree(lines.get(1));
        assertThat(missing.path("inputTokens").isNull()).isTrue();
        assertThat(missing.path("cachedInputTokens").isNull()).isTrue();
    }

    @Test
    void disabledDoesNotCreateAFileAndWriteFailureDoesNotEscape() {
        Path file = temp.resolve("disabled.jsonl");
        new LocalCacheUsageRecorder(false, file.toString(), false)
                .record(ModelProvider.OPENAI, "test-model", response(10, 0), NOW, NOW, true);
        assertThat(file).doesNotExist();
        assertThatCode(() -> new LocalCacheUsageRecorder(true, temp.toString(), false)
                .record(ModelProvider.OPENAI, "test-model", response(10, 0), NOW, NOW, true))
                .doesNotThrowAnyException();
    }
}
