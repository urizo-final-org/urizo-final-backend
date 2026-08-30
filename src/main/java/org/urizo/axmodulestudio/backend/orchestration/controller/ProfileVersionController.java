package org.urizo.axmodulestudio.backend.orchestration.controller;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.orchestration.dto.ProfileVersionContract;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionService;

@RestController
@RequestMapping("/internal/ai/profile-versions")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class ProfileVersionController {

    private final ProfileVersionService service;

    public ProfileVersionController(ProfileVersionService service) {
        this.service = service;
    }

    @GetMapping("/{profileVersionId}")
    JsonNode get(
            @PathVariable UUID profileVersionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return service.getActive(authorization, profileVersionId);
    }

    @ExceptionHandler(ProfileVersionException.class)
    ResponseEntity<ProfileVersionContract.PreContextErrorEnvelope> profileFailure(
            ProfileVersionException failure, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(error(
                request,
                failure.code(),
                failure.getMessage(),
                failure.retryable(),
                failure.retryAfterMs()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProfileVersionContract.PreContextErrorEnvelope> invalidPath(
            MethodArgumentTypeMismatchException failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                request,
                "CONTRACT_VALIDATION_FAILED",
                "profileVersionId must be a valid UUID.",
                false,
                null));
    }

    private static ProfileVersionContract.PreContextErrorEnvelope error(
            HttpServletRequest request,
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
        UUID traceId = UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
        return new ProfileVersionContract.PreContextErrorEnvelope(
                ProfileVersionContract.SCHEMA_VERSION,
                UUID.randomUUID(),
                traceId,
                new ProfileVersionContract.ErrorDetail(
                        code,
                        message,
                        retryable,
                        retryAfterMs,
                        ProfileVersionContract.EXECUTION_STATE_NOT_STARTED));
    }
}
