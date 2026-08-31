package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderRetryPolicy.RetryDecision;

public final class ProviderChatGateway implements ProviderChatGatewayPort {

    private final ProviderCapabilityRegistry capabilityRegistry;
    private final ProviderChatAdapterRegistry adapterRegistry;
    private final ProviderErrorNormalizer errorNormalizer;
    private final ProviderRetryPolicy retryPolicy;
    private final Clock clock;
    private final Sleeper sleeper;

    public ProviderChatGateway(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry,
            Clock clock) {
        this(capabilityRegistry, adapterRegistry, new ProviderErrorNormalizer(),
                new ProviderRetryPolicy(), clock, duration -> Thread.sleep(duration.toMillis()));
    }

    ProviderChatGateway(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry,
            ProviderErrorNormalizer errorNormalizer,
            ProviderRetryPolicy retryPolicy,
            Clock clock,
            Sleeper sleeper) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry is required");
        this.adapterRegistry = Objects.requireNonNull(adapterRegistry, "adapterRegistry is required");
        this.errorNormalizer = Objects.requireNonNull(errorNormalizer, "errorNormalizer is required");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper is required");
    }

    @Override
    public ProviderChatResponse chat(ProviderChatRequest request) {
        Objects.requireNonNull(request, "request is required");
        ProviderModelRegistration registration = capabilityRegistry.require(
                request.provider(), request.modelId(),
                request.tools().isEmpty() ? ModelUseCase.CHAT : ModelUseCase.TOOL_CALL);
        ProviderChatAdapter adapter = adapterRegistry.require(request.provider());
        Instant deadline = earlier(request.deadline(), clock.instant().plus(registration.timeout()));

        int completedAttempts = 0;
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_TIMEOUT,
                        "Model provider deadline exceeded.");
            }
            completedAttempts++;
            try {
                return adapter.chat(registration, request);
            }
            catch (ProviderGatewayException failure) {
                throw failure;
            }
            catch (RuntimeException failure) {
                NormalizedProviderError error = errorNormalizer.normalize(failure);
                RetryDecision decision = retryPolicy.evaluate(
                        error,
                        completedAttempts,
                        registration.maxAttempts(),
                        clock.instant(),
                        deadline);
                if (!decision.retry()) {
                    throw new ProviderGatewayException(error.code(), error.message());
                }
                sleep(decision.delay());
            }
        }
    }

    private void sleep(Duration delay) {
        try {
            sleeper.sleep(delay);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_TIMEOUT,
                    "Model provider retry was interrupted.");
        }
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
