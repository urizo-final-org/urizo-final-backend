package org.urizo.axmodulestudio.backend.coding.modelturn;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderGatewayException;

@RestController
@RequestMapping("/internal/coding/model-turns")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingModelTurnController {

    private final CodingModelTurnGuard guard;
    private final CodingModelTurnService service;

    public CodingModelTurnController(CodingModelTurnGuard guard, CodingModelTurnService service) {
        this.guard = guard;
        this.service = service;
    }

    @PostMapping
    ResponseEntity<?> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CodingModelTurnContract.Request request) {
        CodingModelTurnPermit permit = null;
        try {
            permit = guard.reserve(authorization, request);
            if (permit.replay()) {
                return ResponseEntity.ok(permit.cachedResponse());
            }
            CodingModelTurnContract.Response response = service.execute(request);
            guard.complete(permit, response);
            return ResponseEntity.ok(response);
        }
        catch (CodingModelTurnAccessException failure) {
            return accessFailure(request, failure);
        }
        catch (ProviderGatewayException failure) {
            persistFailure(permit, failure.code().name(), retryable(failure.code()));
            return gatewayFailure(request, failure);
        }
        catch (RuntimeException failure) {
            persistFailure(permit, "INTERNAL_TRANSIENT_ERROR", true);
            return gatewayFailure(request, new ProviderGatewayException(
                    ModelGatewayErrorCode.INTERNAL_TRANSIENT_ERROR,
                    "Coding Model Turn failed unexpectedly."));
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<CodingModelTurnContract.PreContextErrorEnvelope> unreadableRequest(
            HttpMessageNotReadableException failure) {
        CodingModelTurnContract.ErrorDetail error = new CodingModelTurnContract.ErrorDetail(
                "CONTRACT_VALIDATION_FAILED",
                "The model turn request does not satisfy the contract.",
                false,
                null);
        return ResponseEntity.badRequest().body(new CodingModelTurnContract.PreContextErrorEnvelope(
                CodingModelTurnContract.SCHEMA_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                error));
    }

    private static ResponseEntity<?> accessFailure(
            CodingModelTurnContract.Request request,
            CodingModelTurnAccessException failure) {
        CodingModelTurnContract.ErrorDetail error = new CodingModelTurnContract.ErrorDetail(
                failure.code(),
                failure.getMessage(),
                failure.retryable(),
                failure.retryAfterMs());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .body(new CodingModelTurnContract.PreContextErrorEnvelope(
                            CodingModelTurnContract.SCHEMA_VERSION,
                            UUID.randomUUID(),
                            request.traceId(),
                            error));
        }
        return ResponseEntity.status(failure.status()).body(jobError(request, error));
    }

    private void persistFailure(CodingModelTurnPermit permit, String code, boolean retryable) {
        if (permit == null || permit.replay()) {
            return;
        }
        try {
            guard.fail(permit, code, retryable);
        }
        catch (RuntimeException ignored) {
            // Preserve the original safe failure. The lease expires and permits a bounded retry.
        }
    }

    private static ResponseEntity<CodingModelTurnContract.JobScopedErrorEnvelope> gatewayFailure(
            CodingModelTurnContract.Request request,
            ProviderGatewayException failure) {
        HttpStatus status = status(failure.code());
        boolean retryable = retryable(failure.code());
        CodingModelTurnContract.ErrorDetail error = new CodingModelTurnContract.ErrorDetail(
                failure.code().name(),
                failure.getMessage(),
                retryable,
                retryable ? 1_000L : null);
        return ResponseEntity.status(status).body(jobError(request, error));
    }

    private static CodingModelTurnContract.JobScopedErrorEnvelope jobError(
            CodingModelTurnContract.Request request,
            CodingModelTurnContract.ErrorDetail error) {
        return new CodingModelTurnContract.JobScopedErrorEnvelope(
                CodingModelTurnContract.SCHEMA_VERSION,
                request.traceId(),
                request.jobId(),
                request.idempotencyKey(),
                error);
    }

    private static HttpStatus status(ModelGatewayErrorCode code) {
        return switch (code) {
            case CONTRACT_VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case MODEL_RESPONSE_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MODEL_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case MODEL_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case MODEL_NOT_CONFIGURED, MODEL_CAPABILITY_UNSUPPORTED,
                    MODEL_PROVIDER_UNAVAILABLE, INTERNAL_TRANSIENT_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static boolean retryable(ModelGatewayErrorCode code) {
        return switch (code) {
            case MODEL_RATE_LIMITED, MODEL_TIMEOUT, MODEL_PROVIDER_UNAVAILABLE,
                    INTERNAL_TRANSIENT_ERROR -> true;
            case CONTRACT_VALIDATION_FAILED, MODEL_NOT_CONFIGURED,
                    MODEL_CAPABILITY_UNSUPPORTED, MODEL_RESPONSE_INVALID -> false;
        };
    }
}
