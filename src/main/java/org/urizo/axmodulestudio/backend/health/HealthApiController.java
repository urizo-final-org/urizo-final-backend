package org.urizo.axmodulestudio.backend.health;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.product.ProductReadiness;

@RestController
@RequestMapping("/api")
public final class HealthApiController {

    private static final String SCHEMA_VERSION = "1.0";

    private final Clock clock;
    private final ObjectProvider<ProductReadiness> productReadiness;

    public HealthApiController(
            Clock clock,
            ObjectProvider<ProductReadiness> productReadiness) {
        this.clock = clock;
        this.productReadiness = productReadiness;
    }

    @GetMapping("/health")
    HealthResponse getHealth(HttpServletRequest request) {
        return new HealthResponse(
                SCHEMA_VERSION,
                traceId(request),
                "UP",
                Instant.now(clock));
    }

    @GetMapping("/readiness")
    ResponseEntity<ReadinessResponse> getReadiness(HttpServletRequest request) {
        ProductReadiness readiness = productReadiness.getIfAvailable();
        if (readiness != null) {
            ProductReadiness.Snapshot snapshot = readiness.snapshot();
            ReadinessResponse response = new ReadinessResponse(
                    SCHEMA_VERSION,
                    traceId(request),
                    snapshot.ready() ? "READY" : "NOT_READY",
                    Instant.now(clock),
                    snapshot.checks().stream()
                            .map(check -> new ReadinessCheck(
                                    check.name(), check.status(), check.required()))
                            .toList());
            return ResponseEntity.status(snapshot.ready() ? HttpStatus.OK
                    : HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
        ReadinessResponse response = new ReadinessResponse(
                SCHEMA_VERSION,
                traceId(request),
                "NOT_READY",
                Instant.now(clock),
                List.of(
                        new ReadinessCheck("core-database", "DOWN", true),
                        new ReadinessCheck("queue", "DOWN", true),
                        new ReadinessCheck("migrations", "DOWN", true)));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
    }

    public record HealthResponse(
            String schemaVersion,
            String traceId,
            String status,
            Instant checkedAt) {
    }

    public record ReadinessResponse(
            String schemaVersion,
            String traceId,
            String status,
            Instant checkedAt,
            List<ReadinessCheck> checks) {
    }

    public record ReadinessCheck(
            String name,
            String status,
            boolean required) {
    }
}
