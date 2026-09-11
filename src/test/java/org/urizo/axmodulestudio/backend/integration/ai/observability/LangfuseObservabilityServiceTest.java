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
                    .contains("\"column\":\"environment\",\"operator\":\"=\",\"value\":\"local\"")
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
    void selectedReadUsesOneExactBoundedFilterRechecksIdentityAndCachesThePage()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String jobId = "11111111-1111-4111-8111-111111111111";
        String traceId = "33333333-3333-4333-8333-333333333333";
        String profileVersionId = "22222222-2222-4222-8222-222222222222";
        String observationTraceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            int call = calls.incrementAndGet();
            assertSafeRequest(endpoint, headers);
            String query = URLDecoder.decode(
                    endpoint.getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query)
                    .contains("\"type\":\"string\",\"column\":\"traceId\"")
                    .contains("\"value\":\"" + observationTraceId + "\"")
                    .contains("\"type\":\"string\",\"column\":\"environment\"")
                    .contains("\"type\":\"datetime\",\"column\":\"startTime\"")
                    .contains("\"operator\":\">=\",\"value\":\"2026-09-01T00:59:00Z\"")
                    .contains("\"operator\":\"<\",\"value\":\"2026-09-01T01:03:00Z\"")
                    .doesNotContain("fields=io", "prompt", "cursor=");
            if (call == 1) {
                assertThat(query)
                        .contains("limit=2")
                        .contains("\"column\":\"name\",\"operator\":\"=\",\"value\":\"axms.node\"")
                        .contains("\"key\":\"jobId\",\"operator\":\"=\",\"value\":\""
                                + jobId + "\"")
                        .contains("\"key\":\"profileVersionId\",\"operator\":\"=\",\"value\":\""
                                + profileVersionId + "\"")
                        .contains("\"key\":\"nodeId\",\"operator\":\"=\",\"value\":\"analyze\"")
                        .contains("\"key\":\"pipelineAttempt\",\"operator\":\"=\",\"value\":\"2\"")
                        .contains("\"key\":\"executionAttempt\",\"operator\":\"=\",\"value\":\"3\"")
                        .contains("\"key\":\"nodeSequence\",\"operator\":\"=\",\"value\":\"7\"")
                        .doesNotContain("parentObservationId");
                return new LangfuseHttpTransport.Response(200, """
                    {"data":[
                      {
                        "id":"node-span-7",
                        "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "parentObservationId":"job-root",
                        "type":"SPAN",
                        "name":"axms.node",
                        "level":"DEFAULT",
                        "environment":"local",
                        "startTime":"2026-09-01T01:00:00Z",
                        "endTime":"2026-09-01T01:00:01Z",
                        "latency":1.0,
                        "metadata":{
                          "jobId":"11111111-1111-4111-8111-111111111111",
                          "traceId":"33333333-3333-4333-8333-333333333333",
                          "profileVersionId":"22222222-2222-4222-8222-222222222222",
                          "nodeId":"analyze",
                          "pipelineAttempt":"2",
                          "executionAttempt":"3",
                          "nodeSequence":"7",
                          "secret":"must-not-escape"
                        }
                      }
                    ],"meta":{"cursor":null}}
                    """);
            }
            assertThat(call).isEqualTo(2);
            assertThat(query)
                    .contains("limit=50")
                    .contains("\"column\":\"parentObservationId\",\"operator\":\"=\",\"value\":\"node-span-7\"")
                    .doesNotContain(
                            "stringObject", "pipelineAttempt", "executionAttempt",
                            "nodeSequence", "\"column\":\"name\"");
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[
                      {
                        "id":"provider-model-7",
                        "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "parentObservationId":"node-span-7",
                        "type":"GENERATION",
                        "name":"axms.model",
                        "level":"DEFAULT",
                        "environment":"local",
                        "startTime":"2026-09-01T01:00:02Z",
                        "endTime":"2026-09-01T01:00:03Z",
                        "latency":1.0,
                        "metadata":{
                          "jobId":"11111111-1111-4111-8111-111111111111",
                          "traceId":"33333333-3333-4333-8333-333333333333",
                          "profileVersionId":"22222222-2222-4222-8222-222222222222",
                          "nodeId":"analyze"
                        }
                      },
                      {
                        "id":"unparented-model",
                        "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "parentObservationId":"other-node",
                        "type":"GENERATION",
                        "name":"axms.model",
                        "level":"DEFAULT",
                        "environment":"local",
                        "startTime":"2026-09-01T01:00:04Z",
                        "endTime":"2026-09-01T01:00:05Z",
                        "latency":1.0,
                        "metadata":{
                          "jobId":"11111111-1111-4111-8111-111111111111",
                          "traceId":"33333333-3333-4333-8333-333333333333",
                          "profileVersionId":"22222222-2222-4222-8222-222222222222",
                          "nodeId":"analyze"
                        }
                      }
                    ],"meta":{"cursor":"next-page-must-not-be-followed"}}
                    """);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LangfuseObservabilityService service = new LangfuseObservabilityService(
                configured(), transport, mapper, CLOCK);

        LangfuseObservabilityService.SelectedObservationsResponse first =
                service.selectedObservations(
                        jobId, traceId, profileVersionId, 2, 3, "analyze", 7,
                        observationTraceId,
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-09-01T01:02:00Z"));
        LangfuseObservabilityService.SelectedObservationsResponse second =
                service.selectedObservations(
                        jobId, traceId, profileVersionId, 2, 3, "analyze", 7,
                        observationTraceId,
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-09-01T01:02:00Z"));

        assertThat(first.status()).isEqualTo(
                LangfuseObservabilityService.Availability.AVAILABLE);
        assertThat(first.observations()).hasSize(2);
        assertThat(first.observations().get(0)).satisfies(row -> {
            assertThat(row.id()).isEqualTo("node-span-7");
            assertThat(row.metadata().pipelineAttempt()).isEqualTo(2);
            assertThat(row.metadata().executionAttempt()).isEqualTo(3);
            assertThat(row.metadata().nodeSequence()).isEqualTo(7);
        });
        assertThat(first.observations().get(1)).satisfies(row -> {
            assertThat(row.id()).isEqualTo("provider-model-7");
            assertThat(row.name()).isEqualTo("axms.model");
            assertThat(row.parentObservationId()).isEqualTo("node-span-7");
            assertThat(row.metadata().pipelineAttempt()).isNull();
            assertThat(row.metadata().executionAttempt()).isNull();
            assertThat(row.metadata().nodeSequence()).isNull();
        });
        assertThat(first.truncated()).isTrue();
        assertThat(mapper.valueToTree(first).toString())
                .doesNotContain("must-not-escape", "next-page-must-not-be-followed");
        assertThat(second).isSameAs(first);
        assertThat(calls).hasValue(2);
    }

    @Test
    void selectedReadIsUnconnectedWithoutNativeTraceAndRejectsLooseIdentityText() {
        AtomicInteger calls = new AtomicInteger();
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            calls.incrementAndGet();
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[{
                      "id":"obs-invalid",
                      "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "parentObservationId":null,
                      "type":"SPAN",
                      "name":"axms.node",
                      "level":"DEFAULT",
                      "environment":"local",
                      "startTime":"2026-09-01T01:00:00Z",
                      "endTime":"2026-09-01T01:00:01Z",
                      "latency":1.0,
                      "metadata":{
                        "jobId":"11111111-1111-4111-8111-111111111111",
                        "profileVersionId":"22222222-2222-4222-8222-222222222222",
                        "nodeId":"analyze",
                        "pipelineAttempt":"02",
                        "executionAttempt":"3",
                        "nodeSequence":"7"
                      }
                    }]}
                    """);
        };
        LangfuseObservabilityService service = service(configured(), transport);

        LangfuseObservabilityService.SelectedObservationsResponse unconnected =
                service.selectedObservations(
                        "11111111-1111-4111-8111-111111111111",
                        "33333333-3333-4333-8333-333333333333",
                        "22222222-2222-4222-8222-222222222222",
                        2, 3, "analyze", 7, null,
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-09-01T01:02:00Z"));
        LangfuseObservabilityService.SelectedObservationsResponse invalid =
                service.selectedObservations(
                        "11111111-1111-4111-8111-111111111111",
                        "33333333-3333-4333-8333-333333333333",
                        "22222222-2222-4222-8222-222222222222",
                        2, 3, "analyze", 7,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-09-01T01:02:00Z"));

        assertThat(unconnected.status()).isEqualTo(
                LangfuseObservabilityService.Availability.UNCONNECTED);
        assertThat(invalid.status()).isEqualTo(
                LangfuseObservabilityService.Availability.UNAVAILABLE);
        assertThat(calls).hasValue(1);
    }

    @Test
    void selectedReadDoesNotChooseAnAmbiguousAnchorOrQueryItsChildren() {
        AtomicInteger calls = new AtomicInteger();
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            calls.incrementAndGet();
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[
                      {
                        "id":"node-span-a",
                        "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "parentObservationId":"job-root",
                        "type":"SPAN",
                        "name":"axms.node",
                        "level":"DEFAULT",
                        "environment":"local",
                        "startTime":"2026-09-01T01:00:00Z",
                        "endTime":"2026-09-01T01:00:01Z",
                        "metadata":{
                          "jobId":"11111111-1111-4111-8111-111111111111",
                          "traceId":"33333333-3333-4333-8333-333333333333",
                          "profileVersionId":"22222222-2222-4222-8222-222222222222",
                          "nodeId":"analyze",
                          "pipelineAttempt":"2",
                          "executionAttempt":"3",
                          "nodeSequence":"7"
                        }
                      },
                      {
                        "id":"node-span-b",
                        "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "parentObservationId":"job-root",
                        "type":"SPAN",
                        "name":"axms.node",
                        "level":"DEFAULT",
                        "environment":"local",
                        "startTime":"2026-09-01T01:00:02Z",
                        "endTime":"2026-09-01T01:00:03Z",
                        "metadata":{
                          "jobId":"11111111-1111-4111-8111-111111111111",
                          "traceId":"33333333-3333-4333-8333-333333333333",
                          "profileVersionId":"22222222-2222-4222-8222-222222222222",
                          "nodeId":"analyze",
                          "pipelineAttempt":"2",
                          "executionAttempt":"3",
                          "nodeSequence":"7"
                        }
                      }
                    ],"meta":{"cursor":null}}
                    """);
        };

        LangfuseObservabilityService.SelectedObservationsResponse response =
                service(configured(), transport).selectedObservations(
                        "11111111-1111-4111-8111-111111111111",
                        "33333333-3333-4333-8333-333333333333",
                        "22222222-2222-4222-8222-222222222222",
                        2, 3, "analyze", 7,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-09-01T01:02:00Z"));

        assertThat(response.status()).isEqualTo(
                LangfuseObservabilityService.Availability.UNCONNECTED);
        assertThat(response.observations()).isEmpty();
        assertThat(calls).hasValue(1);
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

    @Test
    void pagesFilteredJobsAndKindsUpstreamAndIsolatesTheCache() {
        String jobId = "aaaaaaaa-1111-4111-8111-111111111111";
        AtomicInteger calls = new AtomicInteger();
        LangfuseHttpTransport transport = (endpoint, headers, timeout, maximum) -> {
            calls.incrementAndGet();
            assertSafeRequest(endpoint, headers);
            String query = URLDecoder.decode(endpoint.getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("limit=20", "\"key\":\"jobId\"", jobId,
                    "\"column\":\"environment\"", "\"value\":\"local\"",
                    "\"operator\":\">=\",\"value\":\"" + FROM,
                    "\"operator\":\"<\",\"value\":\"" + TO);
            boolean secondPage = query.contains("cursor=cGFnZTI=");
            boolean provider = query.contains("[\"axms.model\"]");
            assertThat(query).contains(provider ? "axms.model" : "axms.node");
            return new LangfuseHttpTransport.Response(200, """
                    {"data":[{"id":"%s","traceId":"trace-1","type":"SPAN",
                      "name":"%s","environment":"local","startTime":"2026-09-01T01:00:00Z",
                      "metadata":{"jobId":"%s"}}],"meta":{"cursor":%s}}
                    """.formatted(secondPage ? "older" : "newer", provider ? "axms.model" : "axms.node",
                            jobId, secondPage ? "null" : "\"cGFnZTI=\""));
        };
        LangfuseObservabilityService service = service(configured(), transport);
        var first = service.observations(FROM, TO, " " + jobId.toUpperCase() + " ", null, 20, "NODE");
        assertThat(first.nextCursor()).isEqualTo("cGFnZTI=");
        assertThat(first.limit()).isEqualTo(20);
        assertThat(first.observations()).extracting(LangfuseObservabilityService.ObservationRow::id)
                .containsExactly("newer");
        assertThat(service.observations(FROM, TO, jobId, null, 20, "NODE")).isSameAs(first);
        var second = service.observations(FROM, TO, jobId, first.nextCursor(), 20, "NODE");
        assertThat(second.nextCursor()).isNull();
        assertThat(second.observations()).extracting(LangfuseObservabilityService.ObservationRow::id)
                .containsExactly("older");
        service.observations(FROM, TO, jobId, null, 20, "PROVIDER");
        assertThat(calls).hasValue(3);
    }

    @Test
    void metricsUseTheSameJobFilterAndDifferentJobsDoNotShareCache() {
        AtomicInteger calls = new AtomicInteger();
        LangfuseObservabilityService service = service(configured(), (endpoint, headers, timeout, maximum) -> {
            calls.incrementAndGet();
            String query = URLDecoder.decode(endpoint.getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("\"type\":\"stringObject\",\"column\":\"metadata\",\"key\":\"jobId\"");
            return new LangfuseHttpTransport.Response(200, "{\"data\":[]}");
        });
        String firstJob = "11111111-1111-4111-8111-111111111111";
        service.metrics(FROM, TO, firstJob);
        service.metrics(FROM, TO, firstJob);
        service.metrics(FROM, TO, "22222222-2222-4222-8222-222222222222");
        assertThat(calls).hasValue(2);
    }

    @Test
    void invalidPageInputsAreRejectedBeforeUpstream() {
        LangfuseObservabilityService service = service(configured(), (endpoint, headers, timeout, maximum) -> {
            throw new AssertionError("invalid input must not reach upstream");
        });
        assertThatThrownBy(() -> service.observations(FROM, TO, "partial", null, 50, "NODE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.metrics(FROM, TO, "partial"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String cursor : new String[]{"bad&environment=prod", " ", "a".repeat(2049)}) {
            assertThatThrownBy(() -> service.observations(FROM, TO, null, cursor, 50, "NODE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int limit : new int[]{0, 51}) {
            assertThatThrownBy(() -> service.observations(FROM, TO, null, null, limit, "NODE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.observations(FROM, TO, null, null, 50, "CUSTOM"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedOrRepeatingUpstreamCursorIsUnavailableAndCannotAdvance() {
        for (String cursor : new String[]{"\"bad&query\"", "123", "\"\"", "\"cGFnZTI=\""}) {
            LangfuseObservabilityService service = service(configured(), (endpoint, headers, timeout, maximum) ->
                    new LangfuseHttpTransport.Response(200, "{\"data\":[],\"meta\":{\"cursor\":" + cursor + "}}"));
            var response = service.observations(FROM, TO, null, "cGFnZTI=", 20, "NODE");
            assertThat(response.status()).isEqualTo(LangfuseObservabilityService.Availability.UNAVAILABLE);
            assertThat(response.nextCursor()).isNull();
            assertThat(response.limit()).isEqualTo(20);
        }
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
