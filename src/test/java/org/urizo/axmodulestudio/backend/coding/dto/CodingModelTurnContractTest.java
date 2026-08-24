package org.urizo.axmodulestudio.backend.coding.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class CodingModelTurnContractTest {

    private static final Path FIXTURES = Path.of("contracts", "fixtures", "coding-agent");
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void readsTheAuthoritativeToolMultiturnAndStructuredGoldenRequests() {
        assertThatCode(() -> read("model-turn.request.valid.json")).doesNotThrowAnyException();
        assertThatCode(() -> read("model-turn.multiturn.request.valid.json")).doesNotThrowAnyException();
        assertThatCode(() -> read("model-turn.structured.request.valid.json")).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheAuthoritativeUnknownFieldAndVersionFixtures() {
        assertThatThrownBy(() -> read("model-turn.unknown-field.invalid.json"))
                .hasMessageNotContaining("OPENAI");
        assertThatThrownBy(() -> read("model-turn.unknown-version.invalid.json"))
                .hasMessageContaining("Unsupported model turn schemaVersion")
                .hasMessageNotContaining("messages");
    }

    @Test
    void requestAccessorsReturnDefensiveJsonCopies() throws Exception {
        CodingModelTurnContract.Request request = read("model-turn.request.valid.json");

        request.messages().get(0).deepCopy().path("content");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.messages().get(0))
                .put("content", "mutated outside");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.responseFormat())
                .put("type", "JSON_SCHEMA");

        assertThat(request.messages().get(0).path("content").textValue())
                .isEqualTo("Plan within the approved repository scope.");
        assertThat(request.responseFormat().path("type").textValue()).isEqualTo("TEXT");
    }

    private CodingModelTurnContract.Request read(String name) throws Exception {
        return objectMapper.readValue(
                Files.readAllBytes(FIXTURES.resolve(name)),
                CodingModelTurnContract.Request.class);
    }
}
