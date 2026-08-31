package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

@RestController
@Profile("dev")
@Validated
@RequestMapping("/internal/dev/provider-credentials")
public class LocalProviderCredentialController {

    private final LocalProviderSecretService secretService;
    private final LocalDevRequestGuard requestGuard;
    private final ProviderConnectionTestService connectionTestService;

    public LocalProviderCredentialController(
            LocalProviderSecretService secretService,
            LocalDevRequestGuard requestGuard,
            ProviderConnectionTestService connectionTestService) {
        this.secretService = secretService;
        this.requestGuard = requestGuard;
        this.connectionTestService = connectionTestService;
    }

    @GetMapping
    CredentialOverview overview(HttpServletRequest request) {
        return new CredentialOverview(
                requestGuard.csrfToken(request),
                secretService.statuses(),
                Instant.now());
    }

    @PutMapping("/{provider}")
    ResponseEntity<ProviderCredentialStatus> store(
            @PathVariable ModelProvider provider,
            @Valid @RequestBody StoreCredentialRequest body,
            HttpServletRequest request) {
        requestGuard.requireMutation(request);
        return ResponseEntity.ok(secretService.store(provider, body.getCredential()));
    }

    @PostMapping("/{provider}/test")
    ResponseEntity<ProviderConnectionTestResult> test(
            @PathVariable ModelProvider provider,
            HttpServletRequest request) {
        requestGuard.requireMutation(request);
        return ResponseEntity.ok(connectionTestService.test(provider));
    }

    @DeleteMapping("/{provider}")
    ResponseEntity<ProviderCredentialStatus> delete(
            @PathVariable ModelProvider provider,
            HttpServletRequest request) {
        requestGuard.requireMutation(request);
        return ResponseEntity.ok(secretService.delete(provider));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<SafeError> securityFailure(SecurityException failure) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new SafeError("LOCAL_CMS_ACCESS_DENIED", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<SafeError> validationFailure(IllegalArgumentException failure) {
        return ResponseEntity.badRequest()
                .body(new SafeError("LOCAL_CMS_VALIDATION_FAILED", failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<SafeError> storageFailure(DataAccessException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new SafeError("LOCAL_SECRET_STORE_UNAVAILABLE", "Local secret store is unavailable."));
    }

    public record CredentialOverview(
            String csrfToken,
            List<ProviderCredentialStatus> providers,
            Instant checkedAt) {
    }

    public record SafeError(String code, String message) {
    }

    public static final class StoreCredentialRequest {

        @NotBlank
        @Size(min = 8, max = 4096)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String credential;

        public String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential;
        }

        @Override
        public String toString() {
            return "StoreCredentialRequest[credential=REDACTED]";
        }
    }
}
