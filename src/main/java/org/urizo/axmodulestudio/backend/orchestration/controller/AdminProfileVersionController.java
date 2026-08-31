package org.urizo.axmodulestudio.backend.orchestration.controller;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorDetail;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorResponse;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.orchestration.dto.AdminProfileVersionContract.CreateRequest;
import org.urizo.axmodulestudio.backend.orchestration.dto.AdminProfileVersionContract.ProfileVersionView;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionService;

@RestController
@Profile("local-full")
@RequestMapping("/api/admin/ai/profile-versions")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class AdminProfileVersionController {

    private final ProfileVersionService service;

    public AdminProfileVersionController(ProfileVersionService service) {
        this.service = service;
    }

    @GetMapping
    List<ProfileVersionView> list(
            @RequestParam(value = "profileKey", required = false) String profileKey) {
        return service.listAdmin(profileKey).stream().map(ProfileVersionView::from).toList();
    }

    @PostMapping
    ResponseEntity<ProfileVersionView> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ProfileVersionView.from(
                service.createDraft(request.profileKey(), request.snapshot())));
    }

    @PostMapping("/{profileVersionId}/activate")
    ProfileVersionView activate(@PathVariable UUID profileVersionId) {
        return ProfileVersionView.from(service.activate(profileVersionId));
    }

    @ExceptionHandler(ProfileVersionException.class)
    ResponseEntity<ErrorResponse> profileFailure(
            ProfileVersionException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request,
                failure.code(),
                failure.getMessage(),
                failure.retryable(),
                failure.retryAfterMs()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> malformedRequest(
            Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request,
                "CONTRACT_VALIDATION_FAILED",
                "The request does not satisfy the Profile Version contract.",
                false,
                null));
    }

    private static ErrorResponse error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        UUID traceId = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        return new ErrorResponse(
                "1.0", traceId, new ErrorDetail(code, message, retryable, retryAfterMs));
    }
}
