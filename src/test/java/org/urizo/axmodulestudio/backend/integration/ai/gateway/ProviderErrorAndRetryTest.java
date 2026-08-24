package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderRetryPolicy.RetryStopReason;

class ProviderErrorAndRetryTest {

    private final ProviderErrorNormalizer normalizer = new ProviderErrorNormalizer();
    private final ProviderRetryPolicy retryPolicy = new ProviderRetryPolicy();

    @Test
    void providerFailuresMapToStableRetrySemantics() {
        assertThat(normalizer.normalize(new ProviderFailure(
                ProviderFailureKind.RATE_LIMITED,
                Duration.ofSeconds(2))))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ModelGatewayErrorCode.MODEL_RATE_LIMITED);
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                });
        assertThat(normalizer.normalize(new TimeoutException("provider payload is not propagated")))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ModelGatewayErrorCode.MODEL_TIMEOUT);
                    assertThat(error.retryable()).isTrue();
                });
        assertThat(normalizer.normalize(new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null)))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.retryAfter()).isNull();
                });
    }

    @Test
    void rawProviderExceptionMessageNeverEntersTheNormalizedError() {
        String credentialProbe = "credential-probe-value-that-must-never-leak";

        NormalizedProviderError error = normalizer.normalize(new RuntimeException(credentialProbe));

        assertThat(error.toString()).doesNotContain(credentialProbe);
        assertThat(error.message()).isEqualTo("Model provider response failed validation.");
    }

    @Test
    void retryKeepsTheOriginalDeadlineAndAttemptBudget() {
        Instant now = Instant.parse("2026-08-11T03:00:00Z");
        NormalizedProviderError timeout = normalizer.normalize(new TimeoutException());

        assertThat(retryPolicy.evaluate(timeout, 1, 3, now, now.plusSeconds(1)))
                .satisfies(decision -> {
                    assertThat(decision.retry()).isTrue();
                    assertThat(decision.delay()).isEqualTo(Duration.ofMillis(250));
                    assertThat(decision.stopReason()).isEqualTo(RetryStopReason.NONE);
                });
        assertThat(retryPolicy.evaluate(timeout, 3, 3, now, now.plusSeconds(1)).stopReason())
                .isEqualTo(RetryStopReason.ATTEMPTS_EXHAUSTED);
        assertThat(retryPolicy.evaluate(timeout, 1, 3, now, now.plusMillis(250)).stopReason())
                .isEqualTo(RetryStopReason.DEADLINE_EXHAUSTED);
    }

    @Test
    void nonRetryableErrorIsNeverRetried() {
        Instant now = Instant.parse("2026-08-11T03:00:00Z");
        NormalizedProviderError invalid = normalizer.normalize(
                new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null));

        assertThat(retryPolicy.evaluate(invalid, 1, 3, now, now.plusSeconds(60)).stopReason())
                .isEqualTo(RetryStopReason.NON_RETRYABLE);
    }
}
