package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

class AdminAuthorizationFilterTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();

    private final AdminAuthorizationFilter filter = new AdminAuthorizationFilter(
            new AuthorizationService(), new ObjectMapper());

    @Test
    void aCustomerOperatorCannotReachPlatformTechnicalConfiguration() throws Exception {
        MockHttpServletResponse response = dispatch(
                "POST", "/api/connectors/" + UUID.randomUUID() + "/sync", generalAdmin());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"FORBIDDEN\"");
    }

    @Test
    void theDeliveryEngineerReachesPlatformTechnicalConfiguration() throws Exception {
        assertThat(dispatch("POST", "/api/projects", superAdmin()).getStatus()).isEqualTo(200);
    }

    @Test
    void everyGuardedRouteIsClosedToACustomerOperator() throws Exception {
        String connector = UUID.randomUUID().toString();
        String knowledgeBase = UUID.randomUUID().toString();
        String[] guarded = {
            "/api/projects",
            "/api/projects/" + PROJECT_ID + "/connectors",
            "/api/connectors/" + connector + "/preview",
            "/api/connectors/" + connector + "/versions/" + UUID.randomUUID() + "/activate",
            "/api/connectors/" + connector + "/sync",
            "/api/knowledge-bases/" + knowledgeBase + "/versions",
            "/api/knowledge-versions/" + UUID.randomUUID() + "/activate",
            "/api/knowledge-bases/" + knowledgeBase + "/rollback",
        };

        for (String path : guarded) {
            assertThat(dispatch("POST", path, generalAdmin()).getStatus())
                    .as("POST %s", path)
                    .isEqualTo(403);
        }
    }

    @Test
    void businessOperationStaysOpenToACustomerOperator() throws Exception {
        assertThat(dispatch("GET", "/api/projects", generalAdmin()).getStatus()).isEqualTo(200);
        assertThat(dispatch("POST", "/api/projects/" + PROJECT_ID + "/chatbots", generalAdmin())
                .getStatus()).isEqualTo(200);
        assertThat(dispatch("GET", "/api/connectors/" + UUID.randomUUID(), generalAdmin())
                .getStatus()).isEqualTo(200);
    }

    /** A read of a guarded path must not be blocked by the rule written for its write. */
    @Test
    void theGuardIsMethodSpecific() throws Exception {
        assertThat(dispatch("GET", "/api/knowledge-bases/" + UUID.randomUUID() + "/versions",
                generalAdmin()).getStatus()).isEqualTo(200);
    }

    @Test
    void aRouteOutsideTheApiBoundaryIsNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/dev/whatever");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    /**
     * Authentication owns the unauthenticated answer. If this filter rejected an absent actor it
     * would answer 403 where the boundary has already decided the route is open.
     */
    @Test
    void anAbsentActorIsLeftToTheAuthenticationFilter() throws Exception {
        assertThat(dispatch("POST", "/api/projects", null).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse dispatch(String method, String path, ActorContext actor)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, UUID.randomUUID().toString());
        if (actor != null) {
            request.setAttribute(ProductionAuthFilter.ACTOR_ATTRIBUTE, actor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static ActorContext superAdmin() {
        return new ActorContext(UUID.randomUUID(), AdminRole.SUPER_ADMIN, Set.of());
    }

    private static ActorContext generalAdmin() {
        return new ActorContext(UUID.randomUUID(), AdminRole.GENERAL_ADMIN, Set.of(PROJECT_ID));
    }
}
