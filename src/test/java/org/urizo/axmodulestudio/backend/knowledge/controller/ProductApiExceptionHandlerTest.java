package org.urizo.axmodulestudio.backend.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

class ProductApiExceptionHandlerTest {

    private static final Set<String> PUBLIC_CODES = Set.of(
            "VALIDATION_FAILED", "SCHEMA_VERSION_UNSUPPORTED", "INVALID_TRACE_ID",
            "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_REUSED", "AUTHENTICATION_REQUIRED",
            "FORBIDDEN", "RESOURCE_NOT_FOUND", "JOB_STATE_CONFLICT",
            "KNOWLEDGE_VERSION_NOT_ACTIVE", "CONNECTOR_SPEC_INVALID",
            "CONNECTOR_RESPONSE_INVALID", "LLM_NOT_CONFIGURED", "INTERNAL_ERROR",
            "IDEMPOTENCY_REQUEST_IN_PROGRESS", "RATE_LIMITED", "UPSTREAM_SERVICE_ERROR",
            "PROVIDER_UNAVAILABLE", "SERVICE_NOT_READY", "UPSTREAM_TIMEOUT");

    @ParameterizedTest
    @CsvSource({
            "CONTRACT_VALIDATION_FAILED,BAD_REQUEST,VALIDATION_FAILED",
            "PROJECT_NOT_FOUND,NOT_FOUND,RESOURCE_NOT_FOUND",
            "STATE_VERSION_CONFLICT,CONFLICT,JOB_STATE_CONFLICT",
            "ACTIVE_KNOWLEDGE_REQUIRED,CONFLICT,KNOWLEDGE_VERSION_NOT_ACTIVE",
            "CONNECTOR_FIXTURE_REQUIRED,UNPROCESSABLE_ENTITY,CONNECTOR_SPEC_INVALID"
    })
    void mapsInternalFailuresToThePublicOpenApiVocabulary(
            String internalCode, HttpStatus status, String expectedCode) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID traceId = UUID.randomUUID();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, traceId.toString());
        ProductApiExceptionHandler handler = new ProductApiExceptionHandler();

        ResponseEntity<ProductApiContract.ErrorEnvelope> response = handler.productFailure(
                new ProductApiException(internalCode, "Safe public message.", status), request);

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().traceId()).isEqualTo(traceId);
        assertThat(response.getBody().error().code()).isEqualTo(expectedCode);
        assertThat(PUBLIC_CODES).contains(response.getBody().error().code());
    }
}
