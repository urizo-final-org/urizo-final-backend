package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.util.Locale;
import java.util.Objects;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

final class LangfuseModelObservationHandler
        implements ObservationHandler<ChatModelObservationContext> {

    static final String OBSERVATION_NAME = "axms.model";
    private static final Object SPAN_KEY = LangfuseModelObservationHandler.class.getName() + ".span";
    private static final Object SCOPE_KEY = LangfuseModelObservationHandler.class.getName() + ".scope";
    private static final Object START_NANOS_KEY =
            LangfuseModelObservationHandler.class.getName() + ".startNanos";
    private static final String FAILURE_CODE = "MODEL_CALL_FAILED";

    private final Tracer tracer;

    LangfuseModelObservationHandler(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer is required");
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        Span span = null;
        try {
            String provider = safeProvider(context.getOperationMetadata().provider());
            String model = requestModel(context);
            span = tracer.spanBuilder(OBSERVATION_NAME)
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("langfuse.observation.type", "generation")
                    .setAttribute("langfuse.environment", LangfuseObservabilityService.ENVIRONMENT)
                    .setAttribute("gen_ai.operation.name", "chat")
                    .setAttribute("gen_ai.provider.name", provider)
                    .setAttribute("gen_ai.system", provider)
                    .setAttribute("langfuse.observation.metadata.provider", provider)
                    .startSpan();
            putModel(span, model, true);
            putBusinessIdentifiers(span, ModelObservationScope.current());
            context.put(SPAN_KEY, span);
            context.put(START_NANOS_KEY, System.nanoTime());
        }
        catch (RuntimeException ignored) {
            // Telemetry must never change a provider call outcome.
            if (span != null) {
                try {
                    span.end();
                }
                catch (RuntimeException ignoredEndFailure) {
                    // Export is fail-open.
                }
            }
            safeRemove(context, SPAN_KEY);
            safeRemove(context, START_NANOS_KEY);
        }
    }

    @Override
    public void onScopeOpened(ChatModelObservationContext context) {
        Scope scope = null;
        try {
            Span span = context.get(SPAN_KEY);
            if (span != null) {
                scope = span.makeCurrent();
                context.put(SCOPE_KEY, scope);
            }
        }
        catch (RuntimeException ignored) {
            // The provider call continues without an active telemetry scope.
            if (scope != null) {
                try {
                    scope.close();
                }
                catch (RuntimeException ignoredCloseFailure) {
                    // Scope cleanup is best-effort.
                }
            }
        }
    }

    @Override
    public void onError(ChatModelObservationContext context) {
        try {
            Span span = context.get(SPAN_KEY);
            if (span != null) {
                span.setStatus(StatusCode.ERROR)
                        .setAttribute("langfuse.observation.level", "ERROR")
                        .setAttribute("langfuse.observation.metadata.errorCode", FAILURE_CODE);
            }
        }
        catch (RuntimeException ignored) {
            // Never record the raw exception and never replace it with a telemetry failure.
        }
    }

    @Override
    public void onScopeClosed(ChatModelObservationContext context) {
        Scope scope = null;
        try {
            scope = context.get(SCOPE_KEY);
        }
        catch (RuntimeException ignored) {
            // Continue with best-effort cleanup below.
        }
        try {
            context.remove(SCOPE_KEY);
        }
        catch (RuntimeException ignored) {
            // Continue closing the scope when it was obtained.
        }
        if (scope != null) {
            try {
                scope.close();
            }
            catch (RuntimeException ignored) {
                // Scope cleanup cannot replace the provider outcome.
            }
        }
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        Span span = context.get(SPAN_KEY);
        if (span == null) {
            return;
        }
        try {
            ChatResponseMetadata metadata = context.getResponse() == null
                    ? null : context.getResponse().getMetadata();
            String model = metadata == null || blank(metadata.getModel())
                    ? requestModel(context) : metadata.getModel().strip();
            putModel(span, model, false);
            if (metadata != null) {
                putUsage(span, metadata.getUsage());
            }
            Long startNanos = context.get(START_NANOS_KEY);
            if (startNanos != null) {
                long latencyMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                span.setAttribute("langfuse.observation.metadata.latencyMs", latencyMs);
            }
        }
        catch (RuntimeException ignored) {
            // Response metadata is best-effort and never changes the provider outcome.
        }
        finally {
            try {
                span.end();
            }
            catch (RuntimeException ignored) {
                // Export is fail-open.
            }
            safeRemove(context, SPAN_KEY);
            safeRemove(context, START_NANOS_KEY);
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    private static void putUsage(Span span, Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return;
        }
        Integer input = usage.getPromptTokens();
        Integer output = usage.getCompletionTokens();
        if (input != null && input >= 0) {
            span.setAttribute("gen_ai.usage.input_tokens", input.longValue())
                    .setAttribute("langfuse.observation.metadata.inputTokens", input.longValue());
        }
        if (output != null && output >= 0) {
            span.setAttribute("gen_ai.usage.output_tokens", output.longValue())
                    .setAttribute("langfuse.observation.metadata.outputTokens", output.longValue());
        }
    }

    private static void putModel(Span span, String model, boolean request) {
        if (blank(model) || !model.strip().matches("[A-Za-z0-9._:/-]{1,160}")) {
            return;
        }
        String normalized = model.strip();
        span.setAttribute("langfuse.observation.model.name", normalized)
                .setAttribute("langfuse.observation.metadata.model", normalized)
                .setAttribute(request ? "gen_ai.request.model" : "gen_ai.response.model", normalized);
    }

    private static String requestModel(ChatModelObservationContext context) {
        return context.getRequest().getOptions() == null
                ? null : context.getRequest().getOptions().getModel();
    }

    private static void putBusinessIdentifiers(
            Span span, ModelObservationScope.Metadata metadata) {
        if (metadata == null) {
            return;
        }
        if (metadata.jobId() != null) {
            span.setAttribute("langfuse.observation.metadata.jobId",
                    metadata.jobId().toString());
        }
        if (metadata.traceId() != null) {
            span.setAttribute("langfuse.observation.metadata.traceId",
                    metadata.traceId().toString());
        }
        if (metadata.profileVersionId() != null) {
            span.setAttribute("langfuse.observation.metadata.profileVersionId",
                    metadata.profileVersionId().toString());
        }
        if (!blank(metadata.nodeId())
                && metadata.nodeId().matches("[a-z][a-z0-9_-]{0,119}")) {
            span.setAttribute("langfuse.observation.metadata.nodeId", metadata.nodeId());
        }
    }

    private static void safeRemove(Observation.Context context, Object key) {
        try {
            context.remove(key);
        }
        catch (RuntimeException ignored) {
            // Context cleanup is best-effort.
        }
    }

    private static String safeProvider(String provider) {
        if (blank(provider)) {
            return "unknown";
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return normalized.matches("[a-z0-9_]{1,40}") ? normalized : "unknown";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
