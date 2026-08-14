package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

class ProductionAuthFilterTest {

    private static final Instant START = Instant.parse("2026-08-13T00:00:00Z");
    private static final String PASSWORD = "correct-horse-battery";

    private final InMemoryAuthOperations operations = new InMemoryAuthOperations();
    private final PasswordHasher hasher = new PasswordHasher(1_000);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProductionAuthFilter filter;
    private AdminAccount admin;
    private UUID assignedProject;
    private String token;

    @BeforeEach
    void setUp() {
        AuthenticationService authentication = new AuthenticationService(
                operations, hasher, Clock.fixed(START, ZoneOffset.UTC),
                new AuthProperties(Duration.ofHours(8)));
        admin = new AdminAccount(
                UUID.randomUUID(), "customer", hasher.hash(PASSWORD.toCharArray()),
                AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE, START);
        assignedProject = UUID.randomUUID();
        operations.save(admin);
        operations.assign(admin.accountId(), assignedProject);
        token = authentication.login("customer", PASSWORD.toCharArray()).token();
        filter = new ProductionAuthFilter(authentication, objectMapper);
    }

    @Test
    void storesTheServerDerivedActorForAValidSession() throws Exception {
        MockHttpServletRequest request = protectedRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).as("the chain must continue").isNotNull();
        ActorContext actor = ProductionAuthFilter.requireActor(request);
        assertThat(actor.actorId()).isEqualTo(admin.accountId());
        assertThat(actor.role()).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(actor.assignedProjectIds()).containsExactly(assignedProject);
    }

    @Test
    void ignoresAClientSuppliedRoleOrActorHeader() throws Exception {
        MockHttpServletRequest request = protectedRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        request.addHeader("X-Actor-Role", "SUPER_ADMIN");
        request.addHeader("X-Actor-Id", UUID.randomUUID().toString());
        request.setParameter("role", "SUPER_ADMIN");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        ActorContext actor = ProductionAuthFilter.requireActor(request);
        assertThat(actor.role()).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(actor.actorId()).isEqualTo(admin.accountId());
        assertThat(actor.canConfigurePlatform()).isFalse();
    }

    @Test
    void answersUnauthenticatedWithoutContinuingTheChain() throws Exception {
        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).as("a rejected request must not reach the handler").isNull();
        assertThat(request.getAttribute(ProductionAuthFilter.ACTOR_ATTRIBUTE)).isNull();
    }

    @Test
    void returnsTheEstablishedErrorEnvelopeWithTheRequestTraceId() throws Exception {
        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Map<String, Object> body = objectMapper.readValue(
                response.getContentAsString(), new TypeReference<Map<String, Object>>() { });
        assertThat(body).containsEntry("schemaVersion", ProductionAuthFilter.SCHEMA_VERSION);
        assertThat(body).containsEntry("traceId", "trace-1");
        assertThat(body.get("error")).isInstanceOfSatisfying(Map.class, error -> assertThat(error)
                .containsEntry("code", AuthenticationFailedException.CODE)
                .containsEntry("retryable", false));
        assertThat(response.getContentAsString()).doesNotContain(token);
    }

    @Test
    void rejectsAMalformedOrForgedAuthorizationHeader() throws Exception {
        for (String header : new String[] {"", "Basic " + token, "Bearer", "Bearer forged"}) {
            MockHttpServletRequest request = protectedRequest();
            request.addHeader(HttpHeaders.AUTHORIZATION, header);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus())
                    .as("header %s must not authenticate", header)
                    .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Test
    void leavesHealthReadinessLoginAndNonApiPathsOpen() {
        assertThat(filter.shouldNotFilter(request("/api/health"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/readiness"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/internal/dev/product-session"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/projects"))).isFalse();
        // Administrator-facing even though it sits under the internal prefix.
        assertThat(filter.shouldNotFilter(request("/internal/dev/provider-credentials"))).isFalse();
    }

    @Test
    void refusesToTreatAnUnauthenticatedRequestAsAnonymous() {
        assertThatThrownBy(() -> ProductionAuthFilter.requireActor(protectedRequest()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    private MockHttpServletRequest protectedRequest() {
        MockHttpServletRequest request = request("/api/projects");
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, "trace-1");
        return request;
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
