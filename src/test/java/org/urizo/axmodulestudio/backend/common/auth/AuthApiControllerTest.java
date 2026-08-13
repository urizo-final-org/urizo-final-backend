package org.urizo.axmodulestudio.backend.common.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

/**
 * Transport behavior of the authentication boundary.
 *
 * <p>The chain is assembled by hand rather than through a Spring context so the controller, the
 * error advice, and the production filter are exercised together without a database.
 */
class AuthApiControllerTest {

    private static final String IDEMPOTENCY_KEY = "auth-controller-test-key";
    private static final char[] PASSWORD = "correct-horse-battery".toCharArray();

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);
    private final PasswordHasher hasher = new PasswordHasher(1_000);
    private final InMemoryAuthOperations operations = new InMemoryAuthOperations();
    /**
     * Mirrors the running application's Jackson settings.
     *
     * <p>A standalone MockMvc setup does not inherit Boot's auto-configuration, so without this the
     * test would assert a timestamp shape the deployed service never produces.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private MockMvc mockMvc;
    private UUID accountId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        AuthenticationService authentication = new AuthenticationService(
                operations, hasher, clock, new AuthProperties(Duration.ofHours(8)));

        accountId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        operations.createAccount(new AdminAccount(
                accountId, "customer-operator", hasher.hash(PASSWORD.clone()),
                AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE, Instant.now(clock)));
        operations.addMembership(new ProjectMembership(accountId, projectId, Instant.now(clock)));

        mockMvc = MockMvcBuilders.standaloneSetup(new AuthApiController(authentication))
                .setControllerAdvice(new AuthApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(
                        new TraceIdFilter(objectMapper),
                        new ProductionAuthFilter(authentication, objectMapper))
                .build();
    }

    @Test
    void loginReturnsAnOpaqueSessionAndTheServerDerivedActor() throws Exception {
        mockMvc.perform(login("customer-operator", "correct-horse-battery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").value("2026-08-13T17:00:00Z"))
                .andExpect(jsonPath("$.actor.actorId").value(accountId.toString()))
                .andExpect(jsonPath("$.actor.role").value("GENERAL_ADMIN"))
                .andExpect(jsonPath("$.actor.assignedProjectIds[0]").value(projectId.toString()));
    }

    @Test
    void loginWithAWrongPasswordIsRejectedWithoutRevealingTheAccount() throws Exception {
        mockMvc.perform(login("customer-operator", "wrong-password-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.sessionToken").doesNotExist());
    }

    @Test
    void loginResponseCarriesNoCredentialMaterial() throws Exception {
        String body = mockMvc.perform(login("customer-operator", "correct-horse-battery"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("passwordValue")
                .doesNotContain("passwordHash")
                .doesNotContain("pbkdf2-sha256");
    }

    @Test
    void currentSessionReportsTheActorBehindThePresentedToken() throws Exception {
        String token = issuedToken();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor.actorId").value(accountId.toString()))
                .andExpect(jsonPath("$.actor.role").value("GENERAL_ADMIN"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-13T17:00:00Z"));
    }

    @Test
    void currentSessionWithoutASessionIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void logoutRevokesTheSessionSoItStopsWorking() throws Exception {
        String token = issuedToken();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedIdempotencyKeyIsRejectedByTheContract() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Idempotency-Key", "short")
                        .contentType("application/json")
                        .content(loginBody("customer-operator", "correct-horse-battery")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String issuedToken() throws Exception {
        String body = mockMvc.perform(login("customer-operator", "correct-horse-battery"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("sessionToken").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String loginId, String password) {
        return post("/api/auth/login")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType("application/json")
                .content(loginBody(loginId, password));
    }

    private static String loginBody(String loginId, String password) {
        return """
                {"schemaVersion":"1.0","loginId":"%s","passwordValue":"%s"}"""
                .formatted(loginId, password);
    }
}
