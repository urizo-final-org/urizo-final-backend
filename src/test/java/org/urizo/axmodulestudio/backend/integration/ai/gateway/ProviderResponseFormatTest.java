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

    /*
     * The approval screen reads a list of acceptance criteria, so the schema has to be able to
     * say "array of string". Before this the gateway knew only object, string and integer, and a
     * list could travel only inside an open payload that strict structured output rejects.
     */
    @Test
    void carriesAnArrayOfStringsAndRejectsAnElementOfTheWrongType() {
        ProviderResponseFormat format = listFormat();

        JsonNode value = format.validateOrRepair(
                "{\"port\":\"feasible\",\"payload\":{\"planSummary\":\"두 줄을 고칩니다.\","
                        + "\"acceptanceCriteria\":[\"버튼이 잠긴다\",\"이유가 보인다\"]}}");

        assertThat(value.path("payload").path("acceptanceCriteria"))
                .hasSize(2);
        assertThatThrownBy(() -> format.validateOrRepair(
                "{\"port\":\"feasible\",\"payload\":{\"planSummary\":\"ok\","
                        + "\"acceptanceCriteria\":[1]}}"))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID));
    }

    @Test
    void rejectsAnArrayThatDoesNotDeclareItsElementType() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("items");
        schema.putObject("properties").putObject("items").put("type", "array");

        assertThatThrownBy(() -> ProviderResponseFormat.jsonSchema(schema))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderResponseFormat listFormat() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("port").add("payload");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("port").put("type", "string");
        ObjectNode payload = properties.putObject("payload")
                .put("type", "object")
                .put("additionalProperties", false);
        payload.putArray("required").add("planSummary").add("acceptanceCriteria");
        ObjectNode payloadProperties = payload.putObject("properties");
        payloadProperties.putObject("planSummary").put("type", "string");
        payloadProperties.putObject("acceptanceCriteria")
                .put("type", "array")
                .putObject("items").put("type", "string");
        return ProviderResponseFormat.jsonSchema(schema);
    }

    private static ProviderResponseFormat format() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("port").add("payload");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("port").put("type", "string");
        properties.putObject("payload").put("type", "object");
        return ProviderResponseFormat.jsonSchema(schema);
    }
}
