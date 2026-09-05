package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

@RestController
@Profile("dev & local-full")
@RequestMapping("/api/natural-cms/jobs")
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class NaturalCmsCommandController {

    private final NaturalCmsStore store;
    private final AuthService authService;

    public NaturalCmsCommandController(NaturalCmsStore store, AuthService authService) {
        this.store = store;
        this.authService = authService;
    }

    @PostMapping
    ResponseEntity<NaturalCmsContract.JobResponse> create(
            @Valid @RequestBody NaturalCmsContract.CreateJobRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        NaturalCmsContract.JobResponse created = store.create(
                actor(authentication), traceId(servletRequest), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{jobId}")
    NaturalCmsContract.JobResponse get(
            @PathVariable UUID jobId, Authentication authentication) {
        return store.read(actor(authentication), jobId);
    }

    @PostMapping("/{jobId}/decisions")
    NaturalCmsContract.JobResponse decide(
            @PathVariable UUID jobId,
            @Valid @RequestBody NaturalCmsContract.ApprovalDecisionRequest request,
            Authentication authentication) {
        return store.decide(actor(authentication), jobId, request);
    }

    @ExceptionHandler(NaturalCmsException.class)
    ResponseEntity<Map<String, Object>> naturalCmsFailure(NaturalCmsException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "code", failure.code(),
                "message", failure.getMessage(),
                "retryable", failure.retryable()));
    }

    private AuthenticatedActor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
        try {
            return authService.loadActor(UUID.fromString(authentication.getName()));
        }
        catch (IllegalArgumentException failure) {
            throw new AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
    }

    private static UUID traceId(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}
