package org.urizo.axmodulestudio.backend.governance;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;

/** GET only. Decisions stay in each domain's existing controller. */
@RestController
@Profile("local-full")
@RequestMapping("/api/admin/governance")
public class HistoryController {
    private final HistoryService service;
    private final AuthService authService;

    public HistoryController(HistoryService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping("/approvals")
    public ResponseEntity<HistoryContract.Page> approvals(Authentication authentication,
            @RequestParam(defaultValue = "RAG") HistoryContract.Domain domain,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") int limit) {
        requireAdministrator(authentication);
        return response(service.approvals(domain, query, cursor, limit));
    }

    @GetMapping("/runs")
    public ResponseEntity<HistoryContract.Page> runs(Authentication authentication,
            @RequestParam(defaultValue = "ALL") HistoryContract.Category category,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") int limit) {
        requireAdministrator(authentication);
        return response(service.runs(category, query, cursor, limit));
    }

    private void requireAdministrator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
        }
        UUID id;
        try { id = UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
        }
        AuthenticatedActor actor = authService.loadActor(id);
        if (!actor.role().isCmsAdministrator()) throw new AccessDeniedException("Administrator required.");
    }

    private static ResponseEntity<HistoryContract.Page> response(HistoryContract.Page page) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(page);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return error(400, "HISTORY_QUERY_INVALID", "조회 조건을 확인해 주세요."); }

    @ExceptionHandler({DataAccessException.class, HistoryService.HistoryUnavailableException.class})
    ResponseEntity<?> unavailable() {
        return error(503, "HISTORY_UNAVAILABLE", "이력을 조회하지 못했습니다. 서비스와 Migration 적용 상태를 확인해 주세요.");
    }

    private static ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
