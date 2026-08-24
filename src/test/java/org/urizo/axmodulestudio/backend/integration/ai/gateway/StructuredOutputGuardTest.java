package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class StructuredOutputGuardTest {

    private final StructuredOutputGuard guard = new StructuredOutputGuard();

    @Test
    void validOutputDoesNotConsumeTheRepairBudget() {
        AtomicInteger repairs = new AtomicInteger();

        StructuredOutputGuard.ValidatedOutput<String> result = guard.validateOrRepair(
                "VALID",
                "VALID"::equals,
                value -> {
                    repairs.incrementAndGet();
                    return "VALID";
                });

        assertThat(result.value()).isEqualTo("VALID");
        assertThat(result.repaired()).isFalse();
        assertThat(repairs).hasValue(0);
    }

    @Test
    void twentyGoldenCasesPassAfterAtMostOneRepairEach() {
        AtomicInteger repairs = new AtomicInteger();

        for (int index = 0; index < 20; index++) {
            StructuredOutputGuard.ValidatedOutput<String> result = guard.validateOrRepair(
                    "INVALID-" + index,
                    value -> value.startsWith("VALID-"),
                    value -> {
                        repairs.incrementAndGet();
                        return value.replace("INVALID-", "VALID-");
                    });

            assertThat(result.repaired()).isTrue();
            assertThat(result.value()).isEqualTo("VALID-" + index);
        }
        assertThat(repairs).hasValue(20);
    }

    @Test
    void aSecondInvalidCandidateFailsWithoutAnotherRepair() {
        AtomicInteger repairs = new AtomicInteger();

        assertThatThrownBy(() -> guard.validateOrRepair(
                "INVALID",
                "VALID"::equals,
                value -> {
                    repairs.incrementAndGet();
                    return "STILL_INVALID";
                }))
                .isInstanceOf(ProviderGatewayException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
        assertThat(repairs).hasValue(1);
    }
}
