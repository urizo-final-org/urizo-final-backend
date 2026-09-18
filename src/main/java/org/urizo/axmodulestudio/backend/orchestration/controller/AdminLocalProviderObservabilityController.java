package org.urizo.axmodulestudio.backend.orchestration.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.LocalProviderObservability;

@RestController
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
@RequestMapping("/api/admin/ai/observability/local")
public class AdminLocalProviderObservabilityController {
    private final LocalProviderObservability service;
    public AdminLocalProviderObservabilityController(LocalProviderObservability service) { this.service = service; }

    @GetMapping("/metrics")
    LocalProviderObservability.Metrics metrics(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String jobId) { return service.metrics(from, to, jobId); }

    @GetMapping("/token-usage")
    LocalProviderObservability.Tokens tokens(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String jobId) { return service.tokenUsage(from, to, jobId); }

    @GetMapping("/observations")
    LocalProviderObservability.Calls calls(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String jobId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) { return service.observations(from, to, jobId, cursor, limit); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorEnvelope> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ErrorEnvelope(new ErrorDetail("INVALID_OBSERVABILITY_RANGE", error.getMessage())));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ErrorEnvelope> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ErrorEnvelope(new ErrorDetail("LOCAL_OBSERVABILITY_UNAVAILABLE", "로컬 호출 기록을 조회할 수 없습니다.")));
    }
    record ErrorEnvelope(ErrorDetail error) { }
    record ErrorDetail(String code, String message) { }
}
