package org.urizo.axmodulestudio.backend.coding.controller;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingConsoleService;

/**
 * What the LLM DevOps screens read.
 *
 * <p>The path sits under {@code /api/admin/coding/} but deliberately outside
 * {@code /api/admin/coding/guardrail/**}, which SecurityConfig restricts to a super
 * administrator. Choosing the guardrail is not ordinary administration; reading the request you
 * are being asked to approve is, and a general administrator has to be able to do it. Falling
 * through to the chain's {@code anyRequest().hasAnyRole("SUPER_ADMIN", "GENERAL_ADMIN")} is
 * therefore the intended outcome, not an oversight.
 *
 * <p>The role still decides what comes back: {@link CodingConsoleService} omits the technical
 * block entirely for a general administrator.
 */
@RestController
@Validated
@RequestMapping("/api/admin/coding/jobs")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class CodingConsoleController {

    private final CodingConsoleService service;
    private final AuthService authService;

    public CodingConsoleController(CodingConsoleService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping
    CodingConsoleContract.JobList list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(limit);
    }

    @GetMapping("/{jobId}")
    ResponseEntity<CodingConsoleContract.JobDetail> detail(
            @PathVariable UUID jobId, Authentication authentication) {
        CodingConsoleContract.JobDetail detail =
                service.detail(jobId, actor(authentication).role());
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    /** Mirrors CodingHandlerCommandController so both read the session the same way. */
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
}
