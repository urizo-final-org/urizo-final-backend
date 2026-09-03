package org.urizo.axmodulestudio.backend.coding.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingConsoleService;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobIntakeService;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;
import org.urizo.axmodulestudio.backend.coding.service.CodingRunnerService;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

/**
 * What the LLM DevOps screens read and write.
 *
 * <p>The path sits under {@code /api/admin/coding/} but deliberately outside
 * {@code /api/admin/coding/guardrail/**}, which SecurityConfig restricts to a super
 * administrator. Choosing the guardrail is not ordinary administration; asking for a change and
 * reading the request you are being asked to approve is, and a general administrator has to be
 * able to do both. Falling through to the chain's
 * {@code anyRequest().hasAnyRole("SUPER_ADMIN", "GENERAL_ADMIN")} is the intended outcome.
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
    private final CodingJobIntakeService intake;
    private final CodingRunnerService runner;
    private final AuthService authService;

    public CodingConsoleController(
            CodingConsoleService service,
            CodingJobIntakeService intake,
            CodingRunnerService runner,
            AuthService authService) {
        this.service = service;
        this.intake = intake;
        this.runner = runner;
        this.authService = authService;
    }

    /**
     * The request a general administrator types. The Idempotency-Key is accepted but not
     * demanded: a screen that forgets one would otherwise be unable to submit at all, and a
     * server-minted key still protects the single submission it is sent with.
     */
    @PostMapping
    ResponseEntity<CodingHandlerContract.CreateCodingJobResponse> create(
            @RequestBody CodingConsoleContract.CreateJobRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey;
        CodingHandlerContract.CreateCodingJobResponse response = intake.create(
                actor(authentication), traceId(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION,
                        "/api/admin/coding/jobs/" + response.job().jobId())
                .body(response);
    }

    @GetMapping
    CodingConsoleContract.JobList list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(limit);
    }

    /**
     * The execution-history screen's runner warning. A literal path, so Spring matches it
     * before the {@code /{jobId}} template below and the word never reaches the UUID parser.
     */
    @GetMapping("/runner-status")
    CodingConsoleContract.RunnerStatus runnerStatus() {
        CodingRunnerService.Liveness liveness = runner.liveness();
        return new CodingConsoleContract.RunnerStatus(
                "1.0", liveness.alive(), liveness.lastSeenAt());
    }

    /**
     * The bell's feed: other people's decisions and this reader's own waiting approvals.
     *
     * <p>The names are resolved here rather than in the query. The Coding console connection
     * has no grant on the account table, and the authentication service already reads it for
     * every request; borrowing that is cheaper and safer than widening a grant.
     */
    @GetMapping("/notifications")
    CodingConsoleContract.NotificationList notifications(Authentication authentication) {
        AuthenticatedActor actor = actor(authentication);
        List<CodingConsoleContract.Notification> items = new ArrayList<>();
        for (CodingConsoleService.DecisionEvent event
                : service.recentDecisions(actor.actorId(), 20)) {
            items.add(new CodingConsoleContract.Notification(
                    "APPROVAL_DECIDED",
                    event.jobId(),
                    event.requestText(),
                    approvalStageLabel(event.stage()),
                    event.decision(),
                    actorName(event.actorId()),
                    event.actorRole(),
                    event.decidedAt()));
        }
        items.addAll(service.waitingForRole(actor.role(), 20));
        items.sort(Comparator.comparing(
                CodingConsoleContract.Notification::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new CodingConsoleContract.NotificationList("1.0", items);
    }

    /** A deleted or unreadable account must not cost the reader the whole feed. */
    private String actorName(java.util.UUID actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            return authService.loadActor(actorId).name();
        }
        catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The approval names an administrator uses, not the graph's node words. */
    private static String approvalStageLabel(String stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case "SCOPE" -> "계획";
            case "CANDIDATE" -> "미리보기";
            case "GITHUB" -> "코드";
            case "CMS" -> "CMS 반영";
            case "DEPLOY" -> "배포";
            default -> stage;
        };
    }

    @GetMapping("/{jobId}")
    ResponseEntity<CodingConsoleContract.JobDetail> detail(
            @PathVariable UUID jobId, Authentication authentication) {
        CodingConsoleContract.JobDetail detail =
                service.detail(jobId, actor(authentication).role());
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @ExceptionHandler(CodingJobLifecycleException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> commandFailure(
            CodingJobLifecycleException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request, failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> validationFailure(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request, "CONTRACT_VALIDATION_FAILED",
                "요청 형식이 올바르지 않습니다.", false, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<CodingJobLifecycleContract.ErrorEnvelope> databaseFailure(
            DataAccessException failure, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                request, "CODING_HANDLER_STORE_UNAVAILABLE",
                "저장소를 사용할 수 없습니다.", true, 1_000L));
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

    private static UUID traceId(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }

    /** {@code /api/admin/coding/jobs/{jobId}} - the identifier is the sixth segment or absent. */
    private static CodingJobLifecycleContract.ErrorEnvelope error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        String[] path = request.getRequestURI().split("/");
        UUID jobId = path.length > 5 ? parse(path[5]) : null;
        return new CodingJobLifecycleContract.ErrorEnvelope(
                CodingJobLifecycleContract.SCHEMA_VERSION,
                traceId(request),
                jobId,
                new CodingJobLifecycleContract.ErrorDetail(
                        code, message, retryable, retryAfterMs));
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
