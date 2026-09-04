package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderRetryPolicy.RetryDecision;

public final class ProviderChatGateway implements ProviderChatGatewayPort {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ProviderChatGateway.class);

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
        ModelUseCase useCase = !request.tools().isEmpty()
                ? ModelUseCase.TOOL_CALL
                : request.responseFormat().structured()
                        ? ModelUseCase.STRUCTURED_OUTPUT
                        : ModelUseCase.CHAT;
        ProviderModelRegistration registration = capabilityRegistry.require(
                request.provider(), request.modelId(), useCase);
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
                ProviderChatResponse response = adapter.chat(registration, request);
                if (!response.finishReason().completed()) {
                    // Which ending it was, in the message. The stored turn keeps only the
                    // failure code, and every non-"stop" ending a provider has - a filter,
                    // a recitation stop, a malformed tool call - lands on this one code.
                    // Measured 2026-09-03: two identical runs died here and the record could
                    // not say which of them it had been.
                    throw new ProviderGatewayException(
                            ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                            "Model provider returned an incomplete response: "
                                    + registration.provider() + " finished as "
                                    + response.finishReason());
                }
                return response;
            }
            catch (ProviderGatewayException failure) {
                throw failure;
            }
            catch (RuntimeException failure) {
                // The provider's own words, before they are replaced. The normalizer answers
                // with a safe sentence on purpose - it is what reaches an operator - and the
                // original is dropped, so a run that dies here says only "failed validation".
                // Measured 2026-09-03: three providers stopped on that sentence and none of
                // them could be diagnosed from it.
                LOG.warn("Model provider call failed: model={} error={} message={}",
                        registration.modelId(), failure.getClass().getName(),
                        failure.getMessage(), failure);
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
