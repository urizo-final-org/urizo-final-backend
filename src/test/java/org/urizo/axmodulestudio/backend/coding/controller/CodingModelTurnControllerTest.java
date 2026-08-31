package org.urizo.axmodulestudio.backend.coding.controller;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnAccessException;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;

@WebMvcTest(
        controllers = CodingModelTurnController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@Import(SecurityConfig.class)
class CodingModelTurnControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CodingModelTurnGuard guard;

    @MockitoBean
    private CodingModelTurnService service;

    @BeforeEach
    void reserveRequest() {
        when(guard.reserve(any(), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request request = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    request.jobId(), request.idempotencyKey(), UUID.randomUUID());
        });
    }

    @Test
    void returnsTheNormalizedSuccessEnvelope() throws Exception {
        CodingModelTurnContract.Request request = request();
        when(service.execute(any(), any())).thenReturn(response(request));

        mockMvc.perform(post("/internal/coding/model-turns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.turnId").value(request.turnId().toString()))
                .andExpect(jsonPath("$.jobId").value(request.jobId().toString()))
                .andExpect(jsonPath("$.traceId").value(request.traceId().toString()))
                .andExpect(jsonPath("$.assistant.content").value("local normalized response"))
                .andExpect(jsonPath("$.selectedModel.provider").value("OPENAI"));
        verify(guard).complete(any(), any());
    }

    @Test
    void unknownFieldsAreRejectedBeforeAuthorizationOrModelInvocation() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(request());
        payload.put("provider", "OPENAI");

        mockMvc.perform(post("/internal/coding/model-turns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.error.code").value("CONTRACT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        verifyNoInteractions(guard, service);
    }

    @Test
    void unknownNestedMessageFieldsAreRejectedBeforeAuthorization() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(request());
        ((ObjectNode) payload.path("messages").path(0)).put("provider", "OPENAI");

        mockMvc.perform(post("/internal/coding/model-turns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_VALIDATION_FAILED"));
        verifyNoInteractions(guard, service);
    }

    @Test
    void authenticationFailureUsesTheBearerChallengeWithoutEchoingCredential() throws Exception {
        doThrow(new CodingModelTurnAccessException(
                "SERVICE_AUTHENTICATION_FAILED",
                "Service authentication failed.",
                HttpStatus.UNAUTHORIZED)).when(guard).reserve(isNull(), any());

        mockMvc.perform(post("/internal/coding/model-turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error.code").value("SERVICE_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        verifyNoInteractions(service);
    }

    @Test
    void timeoutUsesAJobScopedRetryableEnvelope() throws Exception {
        when(service.execute(any(), any())).thenThrow(new ProviderGatewayException(
                ModelGatewayErrorCode.MODEL_TIMEOUT,
                "Model provider deadline exceeded."));
        CodingModelTurnContract.Request request = request();

        mockMvc.perform(post("/internal/coding/model-turns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.traceId").value(request.traceId().toString()))
                .andExpect(jsonPath("$.jobId").value(request.jobId().toString()))
                .andExpect(jsonPath("$.idempotencyKey").value(request.idempotencyKey()))
                .andExpect(jsonPath("$.error.code").value("MODEL_TIMEOUT"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.retryAfterMs").value(1000));
        // A provider failure carries no envelope diagnostic, so the reply record stays empty.
        verify(guard).fail(any(), org.mockito.ArgumentMatchers.eq("MODEL_TIMEOUT"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void completedIdempotentRequestReplaysWithoutProviderInvocation() throws Exception {
        CodingModelTurnContract.Request request = request();
        doReturn(CodingModelTurnPermit.replay(
                request.jobId(), request.idempotencyKey(), response(request)))
                .when(guard).reserve(any(), any());

        mockMvc.perform(post("/internal/coding/model-turns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistant.content").value("local normalized response"));
        verifyNoInteractions(service);
    }

    private static CodingModelTurnContract.Request request() {
        return new CodingModelTurnContract.Request(
                "1.0",
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                "stage4.model.turn.0002",
                1,
                4,
                "plan",
                "coding-plan-v1",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of("CHAT"),
                List.of(
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                .put("role", "user")
                                .put("content", "Local contract fixture.")),
                List.of(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "TEXT"),
                Instant.parse("2026-08-11T09:00:00Z"));
    }

    private static CodingModelTurnContract.Response response(CodingModelTurnContract.Request request) {
        return new CodingModelTurnContract.Response(
                "1.0",
                request.turnId(),
                request.jobId(),
                request.traceId(),
                request.idempotencyKey(),
                new CodingModelTurnContract.Assistant("assistant", "local normalized response"),
                List.of(),
                CodingModelTurnContract.TextResponseFormat.text(),
                new CodingModelTurnContract.SelectedModel("OPENAI", "local-openai-chat-model"),
                new CodingModelTurnContract.TokenUsage(4, 2, 6),
                15,
                "STOP",
                Instant.parse("2026-08-11T08:00:00Z"));
    }
}
