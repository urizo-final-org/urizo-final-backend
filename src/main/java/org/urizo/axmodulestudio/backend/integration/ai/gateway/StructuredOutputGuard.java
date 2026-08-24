package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class StructuredOutputGuard {

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
