package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ProviderResponseFormatTest {

    @Test
    void carriesTheCanonicalSchemaAndDigestAndReturnsTheExactStructuredObject() {
        ProviderResponseFormat format = format();

        JsonNode value = format.validateOrRepair(
                "Result follows.\n```json\n"
                        + "{\"payload\":{\"summary\":\"ok\"},\"port\":\"feasible\"}"
                        + "\n```");

        assertThat(format.requestContract().path("type").asText())
                .isEqualTo("JSON_SCHEMA");
        assertThat(format.requestContract().path("schemaDigest").asText())
                .isEqualTo(format.schemaDigest());
        ObjectNode expected = JsonNodeFactory.instance.objectNode()
                .put("port", "feasible");
        expected.set("payload", JsonNodeFactory.instance.objectNode().put("summary", "ok"));
        assertThat(value).isEqualTo(expected);
    }

    @Test
    void strictMalformedCorpusFailsAfterTheSingleBoundedRepair() {
        ProviderResponseFormat format = format();

        for (String invalid : List.of(
                "{\"port\":\"feasible\",\"port\":\"infeasible\",\"payload\":{}}",
                "{\"port\":\"feasible\",\"payload\":{}} "
                        + "{\"port\":\"infeasible\",\"payload\":{}}",
                "{\"port\":\"feasible\",\"payload\":{",
                "[\"feasible\",{}]",
                "{\"port\":1,\"payload\":{}}",
                "{\"port\":\"feasible\"}",
                "{\"port\":\"feasible\",\"payload\":{},\"extra\":true}")) {
            assertThatThrownBy(() -> format.validateOrRepair(invalid))
                    .isInstanceOfSatisfying(ProviderGatewayException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID));
        }
    }

    @Test
    void rejectsAContractWhoseDigestDoesNotBindItsSchema() {
        ObjectNode contract = format().requestContract();
        contract.put("schemaDigest", "sha256:" + "0".repeat(64));

        assertThatThrownBy(() -> ProviderResponseFormat.fromContract(contract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void rejectsNestedObjectsThatAreOpenOrHaveOptionalFields() {
        ObjectNode openPayload = schema();
        ((ObjectNode) openPayload.path("properties").path("payload"))
                .remove("additionalProperties");

        assertThatThrownBy(() -> ProviderResponseFormat.jsonSchema(openPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("close every object");

        ObjectNode optionalSummary = schema();
        ((ObjectNode) optionalSummary.path("properties").path("payload"))
                .putArray("required");

        assertThatThrownBy(() -> ProviderResponseFormat.jsonSchema(optionalSummary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require every field");
    }

    @Test
    void rejectsOptionalFieldsAtTheRootOfAStructuredOutput() {
        ObjectNode optionalPayload = schema();
        ((com.fasterxml.jackson.databind.node.ArrayNode) optionalPayload.path("required"))
                .remove(1);

        assertThatThrownBy(() -> ProviderResponseFormat.jsonSchema(optionalPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require every field");
    }

    private static ProviderResponseFormat format() {
        return ProviderResponseFormat.jsonSchema(schema());
    }

    private static ObjectNode schema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("port").add("payload");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("port").put("type", "string");
        ObjectNode payload = properties.putObject("payload")
                .put("type", "object")
                .put("additionalProperties", false);
        payload.putArray("required").add("summary");
        payload.putObject("properties").putObject("summary").put("type", "string");
        return schema;
    }
}
