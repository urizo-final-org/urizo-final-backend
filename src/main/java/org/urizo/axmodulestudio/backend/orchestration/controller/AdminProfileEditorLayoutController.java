package org.urizo.axmodulestudio.backend.orchestration.controller;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorDetail;
import org.urizo.axmodulestudio.backend.auth.dto.response.ErrorResponse;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.orchestration.dto.AdminProfileEditorLayoutContract.EditorLayoutView;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository.SaveResult;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileEditorLayoutService;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

@RestController
@Profile("local-full")
@RequestMapping("/api/admin/ai/profile-versions/{profileVersionId}/editor-layout")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class AdminProfileEditorLayoutController {

    private final ProfileEditorLayoutService service;

    public AdminProfileEditorLayoutController(ProfileEditorLayoutService service) {
        this.service = service;
    }

    @GetMapping
    EditorLayoutView get(@PathVariable UUID profileVersionId) {
        return EditorLayoutView.from(service.get(profileVersionId));
    }

    @PutMapping
    ResponseEntity<EditorLayoutView> save(
            @PathVariable UUID profileVersionId,
            @RequestBody JsonNode request) {
        SaveResult result = service.save(profileVersionId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(EditorLayoutView.from(result.layout()));
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
                "The request does not satisfy the Profile Editor Layout contract.",
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
