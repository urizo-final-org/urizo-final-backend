package org.urizo.axmodulestudio.backend.orchestration.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorDetail;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorResponse;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.orchestration.dto.AdminProfileDefaultTemplateContract.DefaultTemplateView;
import org.urizo.axmodulestudio.backend.orchestration.dto.AdminProfileDefaultTemplateContract.SaveRequest;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileDefaultTemplateService;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

@RestController
@Profile("local-full")
@RequestMapping("/api/admin/ai/profile-templates/{profileKey}")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class AdminProfileDefaultTemplateController {

    private final ProfileDefaultTemplateService service;

    public AdminProfileDefaultTemplateController(ProfileDefaultTemplateService service) {
        this.service = service;
    }

    @GetMapping
    DefaultTemplateView get(@PathVariable String profileKey) {
        return DefaultTemplateView.from(service.get(profileKey));
    }

    @PutMapping
    ResponseEntity<DefaultTemplateView> save(
            @PathVariable String profileKey,
            @RequestBody SaveRequest request) {
        return ResponseEntity.ok(DefaultTemplateView.from(
                service.save(profileKey, request.snapshot())));
    }

    @ExceptionHandler(ProfileVersionException.class)
    ResponseEntity<ErrorResponse> profileFailure(
            ProfileVersionException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(
                request, failure.code(), failure.getMessage(),
                failure.retryable(), failure.retryAfterMs()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedRequest(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request,
                "CONTRACT_VALIDATION_FAILED",
                "The request does not satisfy the default Profile Template contract.",
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
