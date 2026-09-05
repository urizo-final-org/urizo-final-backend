package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-full")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfiguration {

    static final String INSTRUMENTATION_SCOPE = "org.urizo.axmodulestudio.ai";

    @Bean
    LangfuseHttpTransport langfuseHttpTransport(LangfuseProperties properties) {
        return new JdkLangfuseHttpTransport(properties.connectTimeout());
    }

    @Bean(destroyMethod = "close")
    SdkTracerProvider langfuseTracerProvider(LangfuseProperties properties) {
        Resource resource = Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "ax-module-studio-backend",
                AttributeKey.stringKey("deployment.environment.name"),
                LangfuseObservabilityService.ENVIRONMENT,
                AttributeKey.stringKey("langfuse.environment"),
                LangfuseObservabilityService.ENVIRONMENT));
        if (!properties.configured()) {
            return disabledProvider(resource);
        }

        try {
            String credentials = properties.publicKey() + ":" + properties.secretKey();
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(properties.endpoint("/api/public/otel/v1/traces").toString())
                    .setConnectTimeout(properties.connectTimeout())
                    .setTimeout(properties.requestTimeout())
                    .addHeader("Authorization", authorization)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .build();
            return SdkTracerProvider.builder()
                    .setResource(resource)
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                            .setExporterTimeout(properties.requestTimeout())
                            .build())
                    .build();
        }
        catch (RuntimeException ignored) {
            return disabledProvider(resource);
        }
    }

    @Bean
    OpenTelemetry langfuseOpenTelemetry(SdkTracerProvider tracerProvider) {
        try {
            return OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.create(
                            W3CTraceContextPropagator.getInstance()))
                    .build();
        }
        catch (RuntimeException ignored) {
            return OpenTelemetry.noop();
        }
    }

    @Bean
    Tracer langfuseTracer(OpenTelemetry openTelemetry) {
        try {
            return openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        }
        catch (RuntimeException ignored) {
            return OpenTelemetry.noop().getTracer(INSTRUMENTATION_SCOPE);
        }
    }

    @Bean
    LangfuseModelObservationHandler langfuseModelObservationHandler(Tracer tracer) {
        return new LangfuseModelObservationHandler(tracer);
    }

    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> langfuseObservationCustomizer(
            LangfuseModelObservationHandler handler) {
        return registry -> registry.observationConfig().observationHandler(handler);
    }

    private static SdkTracerProvider disabledProvider(Resource resource) {
        return SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOff())
                .build();
    }
}
