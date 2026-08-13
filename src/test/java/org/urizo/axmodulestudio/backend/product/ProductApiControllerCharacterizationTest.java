package org.urizo.axmodulestudio.backend.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

class ProductApiControllerCharacterizationTest {

    private static final UUID TRACE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONNECTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CHATBOT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID JOB_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void preservesTheExistingPublicProductRouteTable() {
        assertThat(routeTable()).containsExactlyInAnyOrderElementsOf(Set.of(
                "POST /api/projects",
                "GET /api/projects",
                "GET /api/projects/{projectId}",
                "POST /api/projects/{projectId}/connectors",
                "GET /api/projects/{projectId}/connectors",
                "GET /api/connectors/{connectorId}",
                "POST /api/connectors/{connectorId}/preview",
                "POST /api/connectors/{connectorId}/versions/{connectorVersionId}/activate",
                "POST /api/connectors/{connectorId}/sync",
                "POST /api/knowledge-bases",
                "GET /api/knowledge-bases",
                "GET /api/knowledge-bases/{knowledgeBaseId}",
                "POST /api/knowledge-bases/{knowledgeBaseId}/versions",
                "GET /api/knowledge-bases/{knowledgeBaseId}/versions",
                "GET /api/knowledge-versions/{knowledgeVersionId}",
                "POST /api/knowledge-versions/{knowledgeVersionId}/activate",
                "POST /api/knowledge-bases/{knowledgeBaseId}/rollback",
                "POST /api/projects/{projectId}/chatbots",
                "GET /api/projects/{projectId}/chatbots",
                "GET /api/chatbots/{chatbotId}",
                "POST /api/chatbots/{chatbotId}/query",
                "GET /api/agent-jobs/{jobId}",
                "GET /api/agent-jobs",
                "POST /api/agent-jobs/{jobId}/cancel",
                "POST /api/agent-jobs/{jobId}/retry"));
    }

    @Test
    void preservesCreatedAndAcceptedResponseMetadataAcrossDomains() {
        ProductService service = mock(ProductService.class);
        ProductApiController controller = new ProductApiController(
                service, service, service, service, service);
        MockHttpServletRequest servletRequest = tracedRequest();
        String key = "characterization.key.0001";

        ProductApiContract.CreateProjectRequest projectRequest =
                new ProductApiContract.CreateProjectRequest("1.0", "Project", null);
        when(service.createProject(TRACE_ID, key, projectRequest)).thenReturn(projectResponse());
        ResponseEntity<ProductApiContract.ProjectResponse> project =
                controller.createProject(key, projectRequest, servletRequest);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(project.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/projects/" + PROJECT_ID);
        verify(service).createProject(TRACE_ID, key, projectRequest);

        ProductApiContract.CreateConnectorRequest connectorRequest = mock(
                ProductApiContract.CreateConnectorRequest.class);
        when(service.createConnector(PROJECT_ID, TRACE_ID, key, connectorRequest))
                .thenReturn(connectorResponse());
        ResponseEntity<ProductApiContract.ConnectorResponse> connector =
                controller.createConnector(PROJECT_ID, key, connectorRequest, servletRequest);
        assertThat(connector.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(connector.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/connectors/" + CONNECTOR_ID);

        ProductApiContract.CreateKnowledgeBaseRequest knowledgeRequest =
                new ProductApiContract.CreateKnowledgeBaseRequest(
                        "1.0", PROJECT_ID, "Knowledge", null);
        when(service.createKnowledgeBase(TRACE_ID, key, knowledgeRequest))
                .thenReturn(knowledgeBaseResponse());
        ResponseEntity<ProductApiContract.KnowledgeBaseResponse> knowledge =
                controller.createKnowledgeBase(key, knowledgeRequest, servletRequest);
        assertThat(knowledge.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(knowledge.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/knowledge-bases/" + KNOWLEDGE_BASE_ID);

        ProductApiContract.CreateChatbotRequest chatbotRequest =
                new ProductApiContract.CreateChatbotRequest("1.0", "Chatbot", KNOWLEDGE_BASE_ID);
        when(service.createChatbot(PROJECT_ID, TRACE_ID, key, chatbotRequest))
                .thenReturn(chatbotResponse());
        ResponseEntity<ProductApiContract.ChatbotResponse> chatbot =
                controller.createChatbot(PROJECT_ID, key, chatbotRequest, servletRequest);
        assertThat(chatbot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(chatbot.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/chatbots/" + CHATBOT_ID);

        ProductApiContract.ConnectorSyncRequest syncRequest =
                new ProductApiContract.ConnectorSyncRequest("1.0", CONNECTOR_ID);
        when(service.syncConnector(CONNECTOR_ID, TRACE_ID, key, syncRequest))
                .thenReturn(jobAcceptedResponse());
        ResponseEntity<ProductApiContract.JobAcceptedResponse> job =
                controller.syncConnector(CONNECTOR_ID, key, syncRequest, servletRequest);
        assertThat(job.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(job.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/agent-jobs/" + JOB_ID);
        assertThat(job.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    private static Set<String> routeTable() {
        Set<String> routes = new TreeSet<>();
        for (Method method : ProductApiController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                Arrays.stream(get.value()).map(path -> "GET /api" + path).forEach(routes::add);
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                Arrays.stream(post.value()).map(path -> "POST /api" + path).forEach(routes::add);
            }
        }
        return routes;
    }

    private static MockHttpServletRequest tracedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, TRACE_ID.toString());
        return request;
    }

    private static ProductApiContract.ProjectResponse projectResponse() {
        return new ProductApiContract.ProjectResponse(
                "1.0", TRACE_ID, PROJECT_ID, "Project", null, "ACTIVE", CREATED_AT);
    }

    private static ProductApiContract.ConnectorResponse connectorResponse() {
        return new ProductApiContract.ConnectorResponse(
                "1.0", TRACE_ID, PROJECT_ID, CONNECTOR_ID, UUID.randomUUID(),
                "CONNECTOR", "DRAFT", "sha256:config", CREATED_AT);
    }

    private static ProductApiContract.KnowledgeBaseResponse knowledgeBaseResponse() {
        return new ProductApiContract.KnowledgeBaseResponse(
                "1.0", TRACE_ID, KNOWLEDGE_BASE_ID, PROJECT_ID,
                "Knowledge", null, null, CREATED_AT);
    }

    private static ProductApiContract.ChatbotResponse chatbotResponse() {
        return new ProductApiContract.ChatbotResponse(
                "1.0", TRACE_ID, CHATBOT_ID, PROJECT_ID, KNOWLEDGE_BASE_ID,
                "Chatbot", "ACTIVE", CREATED_AT);
    }

    private static ProductApiContract.JobAcceptedResponse jobAcceptedResponse() {
        return new ProductApiContract.JobAcceptedResponse(
                "1.0", TRACE_ID, JOB_ID, "CONNECTOR_SYNC", "QUEUED",
                "/api/agent-jobs/" + JOB_ID, CREATED_AT,
                null, CONNECTOR_ID, null);
    }
}
