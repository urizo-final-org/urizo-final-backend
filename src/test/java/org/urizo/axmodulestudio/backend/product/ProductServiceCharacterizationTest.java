package org.urizo.axmodulestudio.backend.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductServiceCharacterizationTest {

    private static final UUID TRACE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID RESOURCE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String KEY = "characterization.key.0001";

    private ProductStore store;
    private ProductService service;

    @BeforeEach
    void setUp() {
        store = mock(ProductStore.class);
        service = new ProductService(store);
    }

    @Test
    void preservesProjectCommandAuthority() {
        ProductApiContract.CreateProjectRequest request =
                new ProductApiContract.CreateProjectRequest("1.0", "Project", null);
        ProductApiContract.ProjectResponse expected = new ProductApiContract.ProjectResponse(
                "1.0", TRACE_ID, RESOURCE_ID, "Project", null, "ACTIVE", Instant.EPOCH);
        when(store.idempotent(eq("CREATE_PROJECT"), eq(KEY), eq(request), eq(201),
                eq(ProductApiContract.ProjectResponse.class), any())).thenReturn(expected);

        assertThat(service.createProject(TRACE_ID, KEY, request)).isSameAs(expected);
        verify(store).idempotent(eq("CREATE_PROJECT"), eq(KEY), eq(request), eq(201),
                eq(ProductApiContract.ProjectResponse.class), any());
    }

    @Test
    void preservesConnectorJobCommandAuthority() {
        ProductApiContract.ConnectorSyncRequest request =
                new ProductApiContract.ConnectorSyncRequest("1.0", RESOURCE_ID);
        ProductApiContract.JobAcceptedResponse expected = accepted("CONNECTOR_SYNC");
        when(store.idempotent(eq("SYNC_CONNECTOR"), eq(KEY), any(), eq(202),
                eq(ProductApiContract.JobAcceptedResponse.class), any())).thenReturn(expected);

        assertThat(service.syncConnector(RESOURCE_ID, TRACE_ID, KEY, request)).isSameAs(expected);
        verify(store).idempotent(eq("SYNC_CONNECTOR"), eq(KEY), any(), eq(202),
                eq(ProductApiContract.JobAcceptedResponse.class), any());
    }

    @Test
    void preservesKnowledgeBuildCommandAuthority() {
        ProductApiContract.StartKnowledgeBuildRequest request =
                new ProductApiContract.StartKnowledgeBuildRequest("1.0", RESOURCE_ID, "v1");
        ProductApiContract.JobAcceptedResponse expected = accepted("KNOWLEDGE_BUILD");
        when(store.idempotent(eq("START_KNOWLEDGE_BUILD"), eq(KEY), any(), eq(202),
                eq(ProductApiContract.JobAcceptedResponse.class), any())).thenReturn(expected);

        assertThat(service.startKnowledgeBuild(RESOURCE_ID, TRACE_ID, KEY, request)).isSameAs(expected);
        verify(store).idempotent(eq("START_KNOWLEDGE_BUILD"), eq(KEY), any(), eq(202),
                eq(ProductApiContract.JobAcceptedResponse.class), any());
    }

    @Test
    void preservesRagQueryCommandAuthority() {
        ProductApiContract.RagQueryRequest request =
                new ProductApiContract.RagQueryRequest("1.0", "query", null, 3);
        ProductApiContract.RagQueryResponse expected = new ProductApiContract.RagQueryResponse(
                "1.0", TRACE_ID, RESOURCE_ID, PROJECT_ID, "REFUSED",
                "No grounding", List.of(), RESOURCE_ID, Instant.EPOCH);
        when(store.idempotent(eq("QUERY_CHATBOT"), eq(KEY), any(), eq(200),
                eq(ProductApiContract.RagQueryResponse.class), any())).thenReturn(expected);

        assertThat(service.query(RESOURCE_ID, TRACE_ID, KEY, request)).isSameAs(expected);
        verify(store).idempotent(eq("QUERY_CHATBOT"), eq(KEY), any(), eq(200),
                eq(ProductApiContract.RagQueryResponse.class), any());
    }

    @Test
    void preservesProductJobRetryCommandAuthority() {
        ProductApiContract.StateMutationRequest request =
                new ProductApiContract.StateMutationRequest("1.0", 3);
        ProductApiContract.AgentJobResponse expected = new ProductApiContract.AgentJobResponse(
                "1.0", TRACE_ID, RESOURCE_ID, PROJECT_ID, "KNOWLEDGE_BUILD", "QUEUED", 4,
                new ProductApiContract.JobProgress(null, 0, null, null, null),
                List.of(), null, Instant.EPOCH, null, Instant.EPOCH, null);
        when(store.idempotent(eq("RETRY_PRODUCT_JOB"), eq(KEY), any(), eq(200),
                eq(ProductApiContract.AgentJobResponse.class), any())).thenReturn(expected);

        assertThat(service.retryJob(RESOURCE_ID, TRACE_ID, KEY, request)).isSameAs(expected);
        verify(store).idempotent(eq("RETRY_PRODUCT_JOB"), eq(KEY), any(), eq(200),
                eq(ProductApiContract.AgentJobResponse.class), any());
    }

    private static ProductApiContract.JobAcceptedResponse accepted(String type) {
        return new ProductApiContract.JobAcceptedResponse(
                "1.0", TRACE_ID, RESOURCE_ID, type, "QUEUED",
                "/api/agent-jobs/" + RESOURCE_ID, Instant.EPOCH,
                null, null, null);
    }
}
