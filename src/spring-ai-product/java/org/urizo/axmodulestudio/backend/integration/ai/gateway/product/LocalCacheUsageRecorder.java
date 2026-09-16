package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ProviderTokenUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;

/** Opt-in, content-free local experiment evidence; independent of Langfuse/exporters. */
@Component
@Profile("dev & !coding-model-turn-local-mock")
final class LocalCacheUsageRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(LocalCacheUsageRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final boolean enabled;
    private final String path;
    private final AtomicBoolean warned = new AtomicBoolean();

    LocalCacheUsageRecorder(
            @Value("${ax.ai.local-cache-usage.enabled:false}") boolean enabled,
            @Value("${ax.ai.local-cache-usage.path:.local/diagnostics/cache-usage.jsonl}") String path,
            @Value("${ax.coding.model-turn-bridge.search-result-grouping-enabled:false}") boolean ignoredLegacyGrouping) {
        this.enabled = enabled;
        this.path = path;
    }

    static LocalCacheUsageRecorder disabled() {
        return new LocalCacheUsageRecorder(false, "", false);
    }

    // Serialize appends from concurrent model calls through this singleton.
    synchronized void record(ModelProvider provider, String model, ChatResponse response,
            Instant startedAt, Instant finishedAt, boolean completed) {
        if (!enabled) return;
        try {
            Usage usage = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getUsage();
            if (usage instanceof EmptyUsage) usage = null;
            ProviderTokenUsage tokens = ProviderTokenUsage.from(provider.name(), usage);
            Integer input = tokens.input(), output = tokens.output(), cached = tokens.cachedInput();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schemaVersion", 1);
            row.put("recordId", UUID.randomUUID().toString());
            row.put("startedAt", startedAt.toString());
            row.put("provider", provider.name());
            row.put("model", model);
            row.put("responseModel", response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getModel());
            row.put("outcome", completed ? "RESPONSE_RETURNED" : "FAILED");
            row.put("usagePresent", usage != null);
            row.put("inputTokens", input);
            row.put("outputTokens", output);
            row.put("cachedInputTokens", cached);
            // OpenAI's prompt count includes cached tokens. Unknown cache != zero cache.
            row.put("uncachedInputTokens", cached == null ? null : input - cached);
            row.put("cacheWriteTokens", null);
            row.put("latencyMs", Math.max(0, Duration.between(startedAt, finishedAt).toMillis()));
            row.put("searchGroupingEnabled", false); // Legacy formatter is no longer a product path.
            ModelObservationScope.Metadata scope = ModelObservationScope.current();
            row.put("jobId", scope == null ? null : scope.jobId());
            row.put("traceId", scope == null ? null : scope.traceId());
            row.put("profileVersionId", scope == null ? null : scope.profileVersionId());
            row.put("nodeId", scope == null ? null : scope.nodeId());
            row.put("turnId", scope == null ? null : scope.turnId());
            row.put("executionAttempt", scope == null ? null : scope.executionAttempt());
            Path target = Path.of(path).toAbsolutePath();
            Files.createDirectories(target.getParent());
            Files.writeString(target, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception ignored) {
            // Never log native usage, exception text, paths, prompts or credentials.
            if (warned.compareAndSet(false, true)) LOG.warn("Local cache usage recording failed; model execution is unaffected.");
        }
    }

}
