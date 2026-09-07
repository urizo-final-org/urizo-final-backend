package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.io.IOException;
import java.util.List;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("local-full")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class W3cTraceContextFilter extends OncePerRequestFilter {

    private static final TextMapGetter<HttpServletRequest> TRACEPARENT_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(HttpServletRequest carrier) {
                    return List.of("traceparent");
                }

                @Override
                public String get(HttpServletRequest carrier, String key) {
                    return "traceparent".equalsIgnoreCase(key)
                            ? carrier.getHeader("traceparent") : null;
                }
            };

    private final OpenTelemetry openTelemetry;

    @Autowired
    public W3cTraceContextFilter(ObjectProvider<OpenTelemetry> openTelemetry) {
        this(openTelemetry.getIfAvailable(OpenTelemetry::noop));
    }

    W3cTraceContextFilter(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Context parent = extract(request);
        try (Scope ignored = parent.makeCurrent()) {
            filterChain.doFilter(request, response);
        }
    }

    private Context extract(HttpServletRequest request) {
        try {
            return openTelemetry.getPropagators().getTextMapPropagator()
                    .extract(Context.root(), request, TRACEPARENT_GETTER);
        }
        catch (RuntimeException ignored) {
            return Context.root();
        }
    }
}
