package org.urizo.axmodulestudio.backend.orchestration.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService;

@RestController
@Profile("local-full")
@RequestMapping("/api/admin/ai/observability")
public class AdminLangfuseObservabilityController {

    private final LangfuseObservabilityService service;

    public AdminLangfuseObservabilityController(LangfuseObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/metrics")
    LangfuseObservabilityService.MetricsResponse metrics(
            @RequestParam String from,
            @RequestParam String to) {
        return service.metrics(from, to);
    }

    @GetMapping("/observations")
    LangfuseObservabilityService.ObservationsResponse observations(
            @RequestParam String from,
            @RequestParam String to) {
        return service.observations(from, to);
    }

    @GetMapping("/scores")
    LangfuseObservabilityService.ScoresResponse scores(
            @RequestParam String from,
            @RequestParam String to) {
        return service.scores(from, to);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorEnvelope> invalidRequest(IllegalArgumentException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ErrorEnvelope(new ErrorDetail(
                        "INVALID_OBSERVABILITY_RANGE", failure.getMessage())));
    }

    record ErrorEnvelope(ErrorDetail error) { }
    record ErrorDetail(String code, String message) { }
}
