package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProviderRetryPolicy {

    public RetryDecision evaluate(
            NormalizedProviderError error,
            int completedAttempts,
            int maxAttempts,
            Instant now,
            Instant deadline) {
        Objects.requireNonNull(error, "error is required");
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(deadline, "deadline is required");
        if (completedAttempts < 1 || maxAttempts < 1 || maxAttempts > 3 || completedAttempts > maxAttempts) {
            throw new IllegalArgumentException("invalid attempt counters");
        }
        if (!error.retryable()) {
            return RetryDecision.stop(RetryStopReason.NON_RETRYABLE);
        }
        if (completedAttempts >= maxAttempts) {
            return RetryDecision.stop(RetryStopReason.ATTEMPTS_EXHAUSTED);
        }

        Duration delay = error.retryAfter();
        if (!now.plus(delay).isBefore(deadline)) {
            return RetryDecision.stop(RetryStopReason.DEADLINE_EXHAUSTED);
        }
        return RetryDecision.retryAfter(delay);
    }

    public enum RetryStopReason {
        NONE,
        NON_RETRYABLE,
        ATTEMPTS_EXHAUSTED,
        DEADLINE_EXHAUSTED
    }

    public record RetryDecision(boolean retry, Duration delay, RetryStopReason stopReason) {

        private static RetryDecision retryAfter(Duration delay) {
            return new RetryDecision(true, delay, RetryStopReason.NONE);
        }

        private static RetryDecision stop(RetryStopReason reason) {
            return new RetryDecision(false, Duration.ZERO, reason);
        }
    }
}
