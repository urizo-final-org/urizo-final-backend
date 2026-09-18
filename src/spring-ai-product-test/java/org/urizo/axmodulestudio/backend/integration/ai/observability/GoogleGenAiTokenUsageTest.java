package org.urizo.axmodulestudio.backend.integration.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

class GoogleGenAiTokenUsageTest {
    @Test void exportsGeminiCacheHitAsExclusiveLangfuseBuckets() throws Exception {
        var exporter = org.mockito.Mockito.mock(io.opentelemetry.sdk.trace.export.SpanExporter.class);
        org.mockito.Mockito.when(exporter.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess());
        org.mockito.Mockito.when(exporter.shutdown())
                .thenReturn(io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess());
        try (var tracerProvider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var handler = new LangfuseModelObservationHandler(tracerProvider.get("test"));
            var usage = GoogleGenAiUsage.from(GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(2000).candidatesTokenCount(12).cachedContentTokenCount(1536).build());
            var context = org.springframework.ai.chat.observation.ChatModelObservationContext.builder()
                    .prompt(new org.springframework.ai.chat.prompt.Prompt("fixture"))
                    .provider("google_genai").build();
            context.setResponse(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(usage).build()));
            handler.onStart(context);
            handler.onStop(context);
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData>> spans =
                    org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
            org.mockito.Mockito.verify(exporter).export(spans.capture());
            assertThat(spans.getValue()).hasSize(1);
            var attributes = spans.getValue().iterator().next().getAttributes();
            var buckets = new com.fasterxml.jackson.databind.ObjectMapper().readTree(attributes.get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.usage_details")));
            assertThat(buckets.path("input").intValue()).isEqualTo(464);
            assertThat(buckets.path("input_cached_tokens").intValue()).isEqualTo(1536);
            assertThat(buckets.path("output").intValue()).isEqualTo(12);
        }
    }

    @Test void preservesNativeCacheHitZeroAndUnknownWithoutDoubleCounting() {
        for (Integer cached : new Integer[]{1536, 0, null, -1, 2001}) {
            var builder = GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(2000).candidatesTokenCount(12);
            if (cached != null) builder.cachedContentTokenCount(cached);
            var usage = GoogleGenAiUsage.from(builder.build());
            for (String provider : new String[]{"google_genai", "GOOGLE_GENAI"}) {
                var actual = ProviderTokenUsage.from(provider, usage);
                assertThat(actual.input()).isEqualTo(2000);
                assertThat(actual.output()).isEqualTo(12);
                if (cached == null || cached < 0 || cached > 2000) {
                    assertThat(actual.cachedInput()).isNull();
                    assertThat(actual.uncachedInput()).isNull();
                } else {
                    assertThat(actual.cachedInput()).isEqualTo(cached);
                    assertThat(actual.uncachedInput() + actual.cachedInput()).isEqualTo(2000);
                }
            }
            assertThat(ProviderTokenUsage.from("openai", usage).cachedInput()).isNull();
        }
    }
}
