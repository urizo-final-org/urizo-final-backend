package org.urizo.axmodulestudio.backend.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class ProductApiContractCharacterizationTest {

    private static final UUID TRACE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RESOURCE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void preservesNullInclusionRulesUsedByPublicPayloads() {
        JsonNode project = objectMapper.valueToTree(new ProductApiContract.ProjectResponse(
                "1.0", TRACE_ID, PROJECT_ID, "Project", null, "ACTIVE", CREATED_AT));
        assertThat(project.has("description")).isFalse();

        JsonNode knowledge = objectMapper.valueToTree(new ProductApiContract.KnowledgeBaseResponse(
                "1.0", TRACE_ID, RESOURCE_ID, PROJECT_ID,
                "Knowledge", null, null, CREATED_AT));
        assertThat(knowledge.has("description")).isFalse();
        assertThat(knowledge.has("activeVersionId")).isTrue();
        assertThat(knowledge.get("activeVersionId").isNull()).isTrue();

        JsonNode accepted = objectMapper.valueToTree(new ProductApiContract.JobAcceptedResponse(
                "1.0", TRACE_ID, RESOURCE_ID, "CONNECTOR_SYNC", "QUEUED",
                "/api/agent-jobs/" + RESOURCE_ID, CREATED_AT,
                null, null, null));
        assertThat(accepted.has("knowledgeVersionId")).isFalse();
        assertThat(accepted.has("connectorVersionId")).isFalse();
        assertThat(accepted.has("configDigest")).isFalse();
    }

    @Test
    void preservesTheFacadeSchemaVersionGuard() {
        assertThat(ProductApiContract.SCHEMA_VERSION).isEqualTo("1.0");
        assertThatThrownBy(() -> new ProductApiContract.CreateProjectRequest(
                "2.0", "Project", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schemaVersion must be 1.0.");
    }
}
