package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev & local-full")
@RequestMapping("/internal/natural-cms/jobs/{jobId}")
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class NaturalCmsStageController {

    private final NaturalCmsStore store;
    private final NaturalCmsStageService stages;

    public NaturalCmsStageController(NaturalCmsStore store, NaturalCmsStageService stages) {
        this.store = store;
        this.stages = stages;
    }

    @GetMapping("/attempts/{pipelineAttempt}")
    NaturalCmsContract.JobResponse get(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization) {
        return store.get(authorization, jobId, pipelineAttempt);
    }

    @PostMapping("/attempts/{pipelineAttempt}/stages/{handlerKey}/executions/{resultId}")
    NaturalCmsContract.StageExecutionResponse execute(
            @PathVariable UUID jobId,
            @PathVariable int pipelineAttempt,
            @PathVariable String handlerKey,
            @PathVariable UUID resultId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Valid @RequestBody NaturalCmsContract.StageExecutionRequest request) {
        if (!handlerKey.equals(request.handlerKey())) {
            throw new IllegalArgumentException("handlerKey does not match the path.");
        }
        return stages.execute(authorization, jobId, pipelineAttempt, resultId, request);
    }

    @ExceptionHandler(NaturalCmsException.class)
    ResponseEntity<Map<String, Object>> naturalCmsFailure(NaturalCmsException failure) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status().value() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(Map.of(
                "code", failure.code(),
                "message", failure.getMessage(),
                "retryable", failure.retryable()));
    }
}
