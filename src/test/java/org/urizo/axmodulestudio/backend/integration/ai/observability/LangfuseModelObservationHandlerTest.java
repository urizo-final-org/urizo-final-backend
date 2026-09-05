package org.urizo.axmodulestudio.backend.integration.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LangfuseModelObservationHandlerTest {

    private static final UUID JOB_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID BUSINESS_TRACE_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String OTEL_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

    @Test
    void successfulProviderObservationContinuesTraceparentWithOnlyAllowlistedData()
            throws Exception {
        CollectingExporter exporter = new CollectingExporter(false);
        try (SdkTracerProvider provider = provider(exporter)) {
            OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(
                            W3CTraceContextPropagator.getInstance()))
                    .build();
            LangfuseModelObservationHandler handler = new LangfuseModelObservationHandler(
                    openTelemetry.getTracer("test"));
            W3cTraceContextFilter filter = new W3cTraceContextFilter(openTelemetry);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("traceparent",
                    "00-" + OTEL_TRACE_ID + "-" + PARENT_SPAN_ID + "-01");

            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
                try (ModelObservationScope ignored = ModelObservationScope.open(
                        JOB_ID, BUSINESS_TRACE_ID, PROFILE_VERSION_ID, "coding_code")) {
                    ChatModelObservationContext context = context("gpt-test");
                    context.setResponse(new ChatResponse(
                            List.of(new Generation(new AssistantMessage("secret completion"))),
                            ChatResponseMetadata.builder()
                                    .model("gpt-test-response")
                                    .usage(new DefaultUsage(12, 4))
                                    .build()));
                    handler.onStart(context);
                    handler.onScopeOpened(context);
                    handler.onScopeClosed(context);
                    handler.onStop(context);
                }
            });

            assertThat(exporter.spans).singleElement().satisfies(span -> {
                assertThat(span.getName()).isEqualTo("axms.model");
                assertThat(span.getTraceId()).isEqualTo(OTEL_TRACE_ID);
                assertThat(span.getParentSpanId()).isEqualTo(PARENT_SPAN_ID);
                assertThat(span.getEvents()).isEmpty();
                assertThat(string(span, "langfuse.observation.type")).isEqualTo("generation");
                assertThat(string(span, "langfuse.environment")).isEqualTo("local");
                assertThat(string(span, "langfuse.observation.model.name"))
                        .isEqualTo("gpt-test-response");
                assertThat(string(span, "langfuse.observation.metadata.jobId"))
                        .isEqualTo(JOB_ID.toString());
                assertThat(string(span, "langfuse.observation.metadata.traceId"))
                        .isEqualTo(BUSINESS_TRACE_ID.toString());
                assertThat(string(span, "langfuse.observation.metadata.profileVersionId"))
                        .isEqualTo(PROFILE_VERSION_ID.toString());
                assertThat(string(span, "langfuse.observation.metadata.nodeId"))
                        .isEqualTo("coding_code");
                assertThat(number(span, "gen_ai.usage.input_tokens")).isEqualTo(12L);
                assertThat(number(span, "gen_ai.usage.output_tokens")).isEqualTo(4L);
                assertThat(span.getAttributes().asMap().keySet())
                        .extracting(AttributeKey::getKey)
                        .containsOnlyElementsOf(Set.of(
                                "langfuse.observation.type", "langfuse.environment",
                                "gen_ai.operation.name", "gen_ai.provider.name", "gen_ai.system",
                                "langfuse.observation.metadata.provider",
                                "langfuse.observation.model.name",
                                "langfuse.observation.metadata.model", "gen_ai.request.model",
                                "gen_ai.response.model", "langfuse.observation.metadata.jobId",
                                "langfuse.observation.metadata.traceId",
                                "langfuse.observation.metadata.profileVersionId",
                                "langfuse.observation.metadata.nodeId", "gen_ai.usage.input_tokens",
                                "gen_ai.usage.output_tokens",
                                "langfuse.observation.metadata.inputTokens",
                                "langfuse.observation.metadata.outputTokens",
                                "langfuse.observation.metadata.latencyMs"));
                assertThat(span.toString()).doesNotContain(
                        "secret completion", "secret prompt", "stackTrace");
            });
        }
    }

    @Test
    void providerErrorRecordsOnlyStableCodeAndNeverRawException() {
        CollectingExporter exporter = new CollectingExporter(false);
        try (SdkTracerProvider provider = provider(exporter)) {
            LangfuseModelObservationHandler handler = new LangfuseModelObservationHandler(
                    provider.get("test"));
            ChatModelObservationContext context = context("gpt-test");
            context.setError(new IllegalStateException("secret provider response"));

            handler.onStart(context);
            handler.onError(context);
            handler.onStop(context);

            assertThat(exporter.spans).singleElement().satisfies(span -> {
                assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
                assertThat(string(span, "langfuse.observation.metadata.errorCode"))
                        .isEqualTo("MODEL_CALL_FAILED");
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.toString()).doesNotContain("secret provider response");
            });
        }
    }

    @Test
    void fallbackAttemptsRemainSeparateActualModelObservations() {
        CollectingExporter exporter = new CollectingExporter(false);
        try (SdkTracerProvider provider = provider(exporter);
                ModelObservationScope ignored = ModelObservationScope.open(
                        JOB_ID, BUSINESS_TRACE_ID, PROFILE_VERSION_ID, "coding_code")) {
            LangfuseModelObservationHandler handler = new LangfuseModelObservationHandler(
                    provider.get("test"));
            ChatModelObservationContext failed = context("gpt-test", "openai");
            handler.onStart(failed);
            handler.onError(failed);
            handler.onStop(failed);
            ChatModelObservationContext succeeded = context("claude-test", "anthropic");
            handler.onStart(succeeded);
            handler.onStop(succeeded);

            assertThat(exporter.spans).hasSize(2);
            assertThat(exporter.spans)
                    .extracting(span -> string(span, "langfuse.observation.metadata.provider"))
                    .containsExactly("openai", "anthropic");
            assertThat(exporter.spans.get(0).getStatus().getStatusCode().name())
                    .isEqualTo("ERROR");
            assertThat(exporter.spans.get(1).getStatus().getStatusCode().name())
                    .isEqualTo("UNSET");
        }
    }

    @Test
    void absentUsageIsOmittedWhileExplicitZeroUsageIsPreserved() {
        CollectingExporter exporter = new CollectingExporter(false);
        try (SdkTracerProvider provider = provider(exporter)) {
            LangfuseModelObservationHandler handler = new LangfuseModelObservationHandler(
                    provider.get("test"));
            ChatModelObservationContext absent = context("gpt-test");
            absent.setResponse(new ChatResponse(
                    List.of(new Generation(new AssistantMessage("hidden"))),
                    ChatResponseMetadata.builder().model("gpt-test").build()));
            handler.onStart(absent);
            handler.onStop(absent);

            ChatModelObservationContext explicitZero = context("gpt-test");
            explicitZero.setResponse(new ChatResponse(
                    List.of(new Generation(new AssistantMessage("hidden"))),
                    ChatResponseMetadata.builder()
                            .model("gpt-test")
                            .usage(new DefaultUsage(0, 0))
                            .build()));
            handler.onStart(explicitZero);
            handler.onStop(explicitZero);

            assertThat(exporter.spans).hasSize(2);
            assertThat(number(exporter.spans.get(0), "gen_ai.usage.input_tokens")).isNull();
            assertThat(number(exporter.spans.get(0), "gen_ai.usage.output_tokens")).isNull();
            assertThat(number(exporter.spans.get(1), "gen_ai.usage.input_tokens"))
                    .isZero();
            assertThat(number(exporter.spans.get(1), "gen_ai.usage.output_tokens"))
                    .isZero();
        }
    }

    @Test
    void telemetryFailuresAreFailOpen() {
        Tracer failingTracer = mock(Tracer.class);
        when(failingTracer.spanBuilder("axms.model"))
                .thenThrow(new IllegalStateException("telemetry unavailable"));
        LangfuseModelObservationHandler failingStart =
                new LangfuseModelObservationHandler(failingTracer);
        assertThatCode(() -> failingStart.onStart(context("gpt-test")))
                .doesNotThrowAnyException();

        CollectingExporter throwingExporter = new CollectingExporter(true);
        try (SdkTracerProvider provider = provider(throwingExporter)) {
            LangfuseModelObservationHandler failingExport =
                    new LangfuseModelObservationHandler(provider.get("test"));
            ChatModelObservationContext context = context("gpt-test");
            failingExport.onStart(context);
            assertThatCode(() -> failingExport.onStop(context))
                    .doesNotThrowAnyException();
        }
    }

    private static SdkTracerProvider provider(SpanExporter exporter) {
        return SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "test-backend")))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    private static ChatModelObservationContext context(String model) {
        return context(model, "openai");
    }

    private static ChatModelObservationContext context(String model, String provider) {
        DefaultChatOptions options = new DefaultChatOptions();
        options.setModel(model);
        return ChatModelObservationContext.builder()
                .prompt(new Prompt("secret prompt", options))
                .provider(provider)
                .build();
    }

    private static String string(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static Long number(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.longKey(key));
    }

    private static final class CollectingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();
        private final boolean fail;

        private CollectingExporter(boolean fail) {
            this.fail = fail;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            if (fail) {
                throw new IllegalStateException("export failed");
            }
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
