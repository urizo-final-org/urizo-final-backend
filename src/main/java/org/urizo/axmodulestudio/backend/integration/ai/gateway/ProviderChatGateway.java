package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
    private final ProviderCallObserver observer;

    public ProviderChatGateway(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry,
            Clock clock) {
        this(capabilityRegistry, adapterRegistry, new ProviderErrorNormalizer(),
                new ProviderRetryPolicy(), clock, duration -> Thread.sleep(duration.toMillis()));
    }

    public ProviderChatGateway(ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry, Clock clock, ProviderCallObserver observer) {
        this(capabilityRegistry, adapterRegistry, new ProviderErrorNormalizer(),
                new ProviderRetryPolicy(), clock, duration -> Thread.sleep(duration.toMillis()), observer);
    }

    ProviderChatGateway(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry,
            ProviderErrorNormalizer errorNormalizer,
            ProviderRetryPolicy retryPolicy,
            Clock clock,
            Sleeper sleeper) {
        this(capabilityRegistry, adapterRegistry, errorNormalizer, retryPolicy, clock, sleeper,
                ProviderCallObserver.NOOP);
    }

    ProviderChatGateway(ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatAdapterRegistry adapterRegistry, ProviderErrorNormalizer errorNormalizer,
            ProviderRetryPolicy retryPolicy, Clock clock, Sleeper sleeper, ProviderCallObserver observer) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry is required");
        this.adapterRegistry = Objects.requireNonNull(adapterRegistry, "adapterRegistry is required");
        this.errorNormalizer = Objects.requireNonNull(errorNormalizer, "errorNormalizer is required");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper is required");
        this.observer = Objects.requireNonNull(observer, "observer is required");
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
        ProviderChatRequest boundedRequest = new ProviderChatRequest(request.provider(), request.modelId(),
                request.messages(), request.tools(), request.responseFormat(), deadline, request.inferenceSettings());

        int completedAttempts = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_TIMEOUT,
                        "Model provider deadline exceeded.");
            }
            completedAttempts++;
            UUID callId = observeStart(registration, completedAttempts);
            try {
                ProviderChatResponse response = adapter.chat(registration, boundedRequest);
                if (callId != null && response.observedUsage() != null) {
                    try { observer.usage(callId, response.observedUsage()); }
                    catch (RuntimeException ignored) { /* Usage persistence is fail-open. */ }
                }
                if (!clock.instant().isBefore(deadline)) {
                    throw new ProviderGatewayException(ModelGatewayErrorCode.MODEL_TIMEOUT,
                            "Model provider deadline exceeded.");
                }
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
                observeFinish(callId, null);
                return response;
            }
            catch (ProviderGatewayException failure) {
                observeFinish(callId, failure.code());
                throw failure;
            }
            catch (RuntimeException failure) {
                NormalizedProviderError error = errorNormalizer.normalize(failure);
                observeFinish(callId, error.code());
                LOG.warn("Model provider call failed: provider={} model={} code={}",
                        registration.provider(), registration.modelId(), error.code());
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

    private UUID observeStart(ProviderModelRegistration registration, int attempt) {
        try { return observer.started(registration.provider(), registration.modelId(), attempt); }
        catch (RuntimeException ignored) { return null; }
    }

    private void observeFinish(UUID callId, ModelGatewayErrorCode code) {
        if (callId == null) return;
        try { observer.finished(callId, code); }
        catch (RuntimeException ignored) { /* Monitoring never changes the provider outcome. */ }
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
