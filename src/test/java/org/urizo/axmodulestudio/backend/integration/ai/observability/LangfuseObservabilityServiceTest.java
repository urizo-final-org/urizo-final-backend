package org.urizo.axmodulestudio.backend.integration.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LangfuseObservabilityServiceTest {

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesOnlyFixedJapanEndpointsAndCachesTypedResults() {
        AtomicInteger calls = new AtomicInteger();
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            calls.incrementAndGet();
            assertSafeRequest(endpoint, headers);
            assertThat(URLDecoder.decode(endpoint.getRawQuery(), StandardCharsets.UTF_8))
                    .contains("\"view\":\"observations\"")
                    .contains("\"column\":\"environment\"")
                    .contains("\"column\":\"name\",\"operator\":\"=\",\"value\":\"axms.model\"")
                    .contains("\"column\":\"type\",\"operator\":\"=\",\"value\":\"GENERATION\"")
                    .contains("\"local\"")
                    .doesNotContain("\"prompt\"", "\"io\"", "completion");
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[{
                      "providedModelName":"gpt-test",
                      "count_count":2,
                      "sum_inputTokens":10,
                      "sum_outputTokens":5,
                      "sum_totalTokens":15,
                      "sum_totalCost":0.01,
                      "p50_latency":12.5,
                      "p95_latency":20.0,
                      "raw":"must-not-escape"
                    }]}
                    """);
        };
        LangfuseObservabilityService service = service(configured(), transport);

        LangfuseObservabilityService.MetricsResponse first = service.metrics(FROM, TO);
        LangfuseObservabilityService.MetricsResponse second = service.metrics(FROM, TO);

        assertThat(first.status()).isEqualTo(
                LangfuseObservabilityService.Availability.AVAILABLE);
        assertThat(first.rows()).singleElement().satisfies(row -> {
            assertThat(row.model()).isEqualTo("gpt-test");
            assertThat(row.inputTokens()).isEqualTo(10);
            assertThat(row.totalCost()).isEqualByComparingTo("0.01");
        });
        assertThat(second).isSameAs(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    void observationReadOmitsIoPromptAndUnknownMetadata() throws Exception {
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            assertSafeRequest(endpoint, headers);
            assertThat(URLDecoder.decode(endpoint.getRawQuery(), StandardCharsets.UTF_8))
                    .contains("fields=core,basic,metadata,model,usage,metrics")
                    .contains("environment=local")
                    .doesNotContain("fields=io", "prompt");
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[{
                      "id":"obs-1",
                      "traceId":"trace-1",
                      "parentObservationId":null,
                      "type":"GENERATION",
                      "name":"axms.model",
                      "level":"DEFAULT",
                      "environment":"local",
                      "startTime":"2026-09-01T01:00:00Z",
                      "endTime":"2026-09-01T01:00:01Z",
                      "model":"gpt-test",
                      "usageDetails":{"input":12,"output":4,"total":16},
                      "latency":1.25,
                      "input":"secret prompt",
                      "output":"secret completion",
                      "metadata":{
                        "jobId":"job-1",
                        "nodeId":"node-1",
                        "provider":"OPENAI",
                        "errorCode":"MODEL_TIMEOUT",
                        "secret":"must-not-escape"
                      }
                    }]}
                    """);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LangfuseObservabilityService service = new LangfuseObservabilityService(
                configured(), transport, mapper, CLOCK);

        LangfuseObservabilityService.ObservationsResponse response =
                service.observations(FROM, TO);
        JsonNode serialized = mapper.valueToTree(response);

        assertThat(response.observations()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("axms.model");
            assertThat(row.model()).isEqualTo("gpt-test");
            assertThat(row.inputTokens()).isEqualTo(12);
            assertThat(row.outputTokens()).isEqualTo(4);
            assertThat(row.latencyMs()).isEqualByComparingTo("1250");
            assertThat(row.metadata().jobId()).isEqualTo("job-1");
            assertThat(row.metadata().errorCode()).isEqualTo("MODEL_TIMEOUT");
        });
        assertThat(serialized.toString())
                .doesNotContain("secret prompt", "secret completion", "must-not-escape");
    }

    @Test
    void scoreReadReturnsOnlyApprovedCoreTypedValuesWithoutRecalculation() {
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            assertSafeRequest(endpoint, headers);
            assertThat(endpoint.getPath()).isEqualTo("/api/public/v3/scores");
            assertThat(URLDecoder.decode(endpoint.getRawQuery(), StandardCharsets.UTF_8))
                    .contains("limit=50", "environment=local")
                    .doesNotContain("fields=");
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[
                      {
                        "id":"score-1",
                        "name":"quality",
                        "value":0.75,
                        "dataType":"NUMERIC",
                        "source":"EVAL",
                        "timestamp":"2026-09-01T03:00:00Z",
                        "environment":"local"
                      },
                      {
                        "id":"score-2",
                        "name":"approved",
                        "value":true,
                        "dataType":"BOOLEAN",
                        "source":"ANNOTATION",
                        "timestamp":"2026-09-01T03:01:00Z",
                        "environment":"local"
                      },
                      {
                        "id":"score-3",
                        "name":"category",
                        "value":"safe-category",
                        "dataType":"CATEGORICAL",
                        "source":"API",
                        "timestamp":"2026-09-01T03:02:00Z",
                        "environment":"local"
                      },
                      {
                        "id":"score-4",
                        "name":"free-text",
                        "value":"sensitive free text",
                        "dataType":"TEXT",
                        "source":"EVAL",
                        "timestamp":"2026-09-01T03:03:00Z",
                        "environment":"local",
                        "comment":"must-not-escape"
                      },
                      {
                        "id":"score-5",
                        "name":"correction",
                        "value":"sensitive correction",
                        "dataType":"CORRECTION",
                        "source":"ANNOTATION",
                        "timestamp":"2026-09-01T03:04:00Z",
                        "environment":"local"
                      }
                    ]}
                    """);
        };

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LangfuseObservabilityService.ScoresResponse response =
                new LangfuseObservabilityService(
                        configured(), transport, mapper, CLOCK).scores(FROM, TO);

        assertThat(response.scores()).extracting(
                LangfuseObservabilityService.ScoreRow::name)
                .containsExactly("quality", "approved", "category");
        assertThat(response.scores().get(0).value().decimalValue())
                .isEqualByComparingTo("0.75");
        assertThat(response.scores().get(1).value().booleanValue()).isTrue();
        assertThat(response.scores().get(2).value().textValue()).isEqualTo("safe-category");
        assertThat(mapper.valueToTree(response).toString()).doesNotContain(
                "sensitive free text", "sensitive correction", "must-not-escape");
    }

    @Test
    void missingOrInvalidConfigurationIsDisabledWithoutCallingUpstream() {
        AtomicInteger calls = new AtomicInteger();
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            calls.incrementAndGet();
            throw new AssertionError("disabled configuration must not call upstream");
        };
        LangfuseProperties partial = new LangfuseProperties(
                "https://cloud.langfuse.com", "pk-test-value", "sk-test-value",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30), 262_144);

        LangfuseObservabilityService.MetricsResponse response =
                service(partial, transport).metrics(FROM, TO);

        assertThat(response.status()).isEqualTo(
                LangfuseObservabilityService.Availability.DISABLED);
        assertThat(response.errorCode()).isEqualTo("LANGFUSE_DISABLED");
        assertThat(calls).hasValue(0);
        assertThat(partial.toString()).doesNotContain(
                "https://cloud.langfuse.com", "pk-test-value", "sk-test-value");
    }

    @Test
    void upstreamFailureReturnsSafeUnavailableResponse() {
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) ->
                new LangfuseHttpTransport.Response(503, "raw provider error with secret");

        LangfuseObservabilityService.ObservationsResponse response =
                service(configured(), transport).observations(FROM, TO);

        assertThat(response.status()).isEqualTo(
                LangfuseObservabilityService.Availability.UNAVAILABLE);
        assertThat(response.errorCode()).isEqualTo("LANGFUSE_UPSTREAM_UNAVAILABLE");
        assertThat(response.observations()).isEmpty();
        assertThat(response.toString()).doesNotContain("raw provider error", "secret");
    }

    @Test
    void rejectsNonUtcOrUnboundedRangesBeforeCallingUpstream() {
        LangfuseHttpTransport unused = (endpoint, headers, timeout, maximum) -> {
            throw new AssertionError("invalid range must not call upstream");
        };
        LangfuseObservabilityService service = service(configured(), unused);

        assertThatThrownBy(() -> service.metrics(
                "2026-09-01T00:00:00+09:00", TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.metrics(
                "2026-07-01T00:00:00Z", TO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void composePassesEmptyDefaultLangfuseEnvironmentToBothRuntimeServices()
            throws IOException {
        String compose = Files.readString(Path.of("compose.dev.yaml"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String springApp = compose.substring(
                compose.indexOf("\n  spring-app:"), compose.indexOf("\n  mcp-server:"));
        String codingRuntime = compose.substring(
                compose.indexOf("\n  coding-runtime:"), compose.indexOf("\n  frontend:"));

        assertLangfusePassthrough(springApp);
        assertLangfusePassthrough(codingRuntime);
    }

    private static LangfuseProperties configured() {
        return new LangfuseProperties(
                "https://jp.cloud.langfuse.com", "public-key", "secret-key",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30), 262_144);
    }

    private static LangfuseObservabilityService service(
            LangfuseProperties properties, LangfuseHttpTransport transport) {
        return new LangfuseObservabilityService(
                properties, transport, new ObjectMapper().findAndRegisterModules(), CLOCK);
    }

    private static void assertSafeRequest(URI endpoint, Map<String, String> headers) {
        assertThat(endpoint.getScheme()).isEqualTo("https");
        assertThat(endpoint.getHost()).isEqualTo("jp.cloud.langfuse.com");
        assertThat(headers).containsEntry("Accept", "application/json");
        assertThat(headers.get("Authorization")).startsWith("Basic ");
        assertThat(endpoint.toString()).doesNotContain("secret-key", "public-key");
    }

    private static void assertLangfusePassthrough(String service) {
        assertThat(service)
                .contains("LANGFUSE_BASE_URL: \"${LANGFUSE_BASE_URL:-}\"")
                .contains("LANGFUSE_PUBLIC_KEY: \"${LANGFUSE_PUBLIC_KEY:-}\"")
                .contains("LANGFUSE_SECRET_KEY: \"${LANGFUSE_SECRET_KEY:-}\"")
                .doesNotContain("LANGFUSE_BASE_URL: https://")
                .doesNotContain("LANGFUSE_PUBLIC_KEY: pk-")
                .doesNotContain("LANGFUSE_SECRET_KEY: sk-");
    }
}
