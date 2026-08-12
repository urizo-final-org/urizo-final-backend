package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProviderGoldenGateRunner {

    private final ProviderCapabilityRegistry capabilityRegistry;
    private final ProviderProbeClientRegistry clientRegistry;
    private final ProviderErrorNormalizer errorNormalizer;
    private final Clock clock;

    public ProviderGoldenGateRunner(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderProbeClientRegistry clientRegistry,
            ProviderErrorNormalizer errorNormalizer,
            Clock clock) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry is required");
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry is required");
        this.errorNormalizer = Objects.requireNonNull(errorNormalizer, "errorNormalizer is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public ProviderGoldenGateReport run(ProviderGoldenGatePlan plan) {
        Objects.requireNonNull(plan, "plan is required");
        ProviderModelRegistration registration = plan.registration();
        ProviderProbeClient client = clientRegistry.require(registration.provider());
        MutableCounters counters = new MutableCounters();

        for (ProviderGoldenCase goldenCase : plan.cases()) {
            capabilityRegistry.require(registration.provider(), registration.modelId(), goldenCase.useCase());
            for (int repetition = 1; repetition <= plan.repetitions(); repetition++) {
                Instant now = clock.instant();
                if (!now.isBefore(plan.deadline())) {
                    throw new ProviderGatewayException(
                            ModelGatewayErrorCode.MODEL_TIMEOUT,
                            "Provider golden gate deadline was exhausted.");
                }

                ProviderProbeRequest request = new ProviderProbeRequest(
                        registration.provider(),
                        registration.modelId(),
                        goldenCase.caseId(),
                        goldenCase.useCase(),
                        goldenCase.fixedPrompt(),
                        repetition,
                        plan.deadline());
                ProviderProbeResult result = invokeSafely(client, request);
                enforceInvariants(goldenCase.useCase(), result);
                counters.add(result);
            }
        }

        if (counters.completedRequests != plan.plannedRequests()) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Provider golden gate did not complete the fixed request plan.");
        }
        return counters.toReport(registration);
    }

    private ProviderProbeResult invokeSafely(ProviderProbeClient client, ProviderProbeRequest request) {
        try {
            return Objects.requireNonNull(client.probe(request), "provider probe result is required");
        }
        catch (ProviderGatewayException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            NormalizedProviderError normalized = errorNormalizer.normalize(failure);
            throw new ProviderGatewayException(normalized.code(), normalized.message());
        }
    }

    private static void enforceInvariants(ModelUseCase useCase, ProviderProbeResult result) {
        if (result.toolExecutions() != 0) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Provider probe attempted unauthorized tool execution.");
        }
        if (useCase == ModelUseCase.TOOL_CALL) {
            if (result.toolCallCandidates() < 1) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                        "Provider did not return the required tool call candidate.");
            }
            return;
        }
        if (!result.responsePresent()) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Provider response was empty.");
        }
        if (useCase == ModelUseCase.STRUCTURED_OUTPUT && !result.structuredOutputSchemaValid()) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Provider structured output failed schema validation.");
        }
    }

    private static final class MutableCounters {

        private int completedRequests;
        private int toolCallCandidates;
        private int inputTokens;
        private int outputTokens;
        private Duration totalLatency = Duration.ZERO;

        private void add(ProviderProbeResult result) {
            completedRequests = Math.addExact(completedRequests, 1);
            toolCallCandidates = Math.addExact(toolCallCandidates, result.toolCallCandidates());
            inputTokens = Math.addExact(inputTokens, result.inputTokens());
            outputTokens = Math.addExact(outputTokens, result.outputTokens());
            totalLatency = totalLatency.plus(result.latency());
        }

        private ProviderGoldenGateReport toReport(ProviderModelRegistration registration) {
            return new ProviderGoldenGateReport(
                    registration.provider(),
                    registration.modelId(),
                    completedRequests,
                    toolCallCandidates,
                    0,
                    inputTokens,
                    outputTokens,
                    totalLatency);
        }
    }
}
