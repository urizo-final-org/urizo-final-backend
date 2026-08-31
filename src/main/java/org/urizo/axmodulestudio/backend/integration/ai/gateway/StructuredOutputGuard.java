package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class StructuredOutputGuard {

    /**
     * One deterministic repair for a correct JSON object wrapped in a fence or
     * short preamble. Callers must still validate the extracted object against
     * their exact contract and must not attempt a second repair.
     */
    public static String extractOutermostJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    /** Returns one object only; trailing prose or a second JSON value fails. */
    public static JsonNode readSingleJsonObject(ObjectMapper objectMapper, String raw) {
        if (raw == null) {
            return null;
        }
        try (JsonParser parser = objectMapper.createParser(raw)) {
            JsonNode value = objectMapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                return null;
            }
            return value;
        }
        catch (JsonProcessingException failure) {
            return null;
        }
        catch (java.io.IOException failure) {
            return null;
        }
    }

    public <T> ValidatedOutput<T> validateOrRepair(
            T candidate,
            Predicate<T> schemaValidator,
            UnaryOperator<T> repair) {
        Objects.requireNonNull(schemaValidator, "schemaValidator is required");
        Objects.requireNonNull(repair, "repair is required");

        if (schemaValidator.test(candidate)) {
            return new ValidatedOutput<>(candidate, false);
        }

        T repaired;
        try {
            repaired = repair.apply(candidate);
        } catch (RuntimeException ignored) {
            throw invalidResponse();
        }
        if (schemaValidator.test(repaired)) {
            return new ValidatedOutput<>(repaired, true);
        }
        throw invalidResponse();
    }

    private static ProviderGatewayException invalidResponse() {
        return new ProviderGatewayException(
                ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                "Structured model output failed schema validation after one repair.");
    }

    public record ValidatedOutput<T>(T value, boolean repaired) {
    }
}
