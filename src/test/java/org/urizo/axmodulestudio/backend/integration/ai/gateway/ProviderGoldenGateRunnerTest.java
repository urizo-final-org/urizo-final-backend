package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ProviderGoldenGateRunnerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T06:00:00Z");

    @Test
    void executesEveryFixedCaseTwentyTimesAndAggregatesOnlySafeMetadata() {
        ProviderModelRegistration registration = registration(Set.of(
                ModelCapability.CHAT,
                ModelCapability.STRUCTURED_OUTPUT,
                ModelCapability.TOOL_CALLING));
        AtomicInteger calls = new AtomicInteger();
        ProviderProbeClient client = client(request -> {
            calls.incrementAndGet();
            boolean toolCase = request.useCase() == ModelUseCase.TOOL_CALL;
            return result(true, true, toolCase ? 1 : 0, 0);
        });

        ProviderGoldenGateReport report = runner(registration, client).run(new ProviderGoldenGatePlan(
                registration,
                List.of(
                        new ProviderGoldenCase("chat", ModelUseCase.CHAT, "Reply with OK."),
                        new ProviderGoldenCase(
                                "structured",
                                ModelUseCase.STRUCTURED_OUTPUT,
                                "Return the fixed schema."),
                        new ProviderGoldenCase(
                                "tool-call",
                                ModelUseCase.TOOL_CALL,
                                "Request the declared no-op tool.")),
                20,
                NOW.plusSeconds(300)));

        assertThat(calls).hasValue(60);
        assertThat(report.completedRequests()).isEqualTo(60);
        assertThat(report.toolCallCandidates()).isEqualTo(20);
        assertThat(report.toolExecutions()).isZero();
        assertThat(report.inputTokens()).isEqualTo(600);
        assertThat(report.outputTokens()).isEqualTo(300);
        assertThat(report.totalTokens()).isEqualTo(900);
    }

    @Test
    void fewerThanTwentyRepetitionsCannotClaimTheLiveGoldenGate() {
        ProviderModelRegistration registration = registration(Set.of(ModelCapability.CHAT));

        assertThatThrownBy(() -> new ProviderGoldenGatePlan(
                registration,
                List.of(new ProviderGoldenCase("chat", ModelUseCase.CHAT, "Reply with OK.")),
                19,
                NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 20 and 100");
    }

    @Test
    void anUnauthorizedToolExecutionFailsClosedOnTheFirstRequest() {
        ProviderModelRegistration registration = registration(Set.of(
                ModelCapability.CHAT,
                ModelCapability.TOOL_CALLING));
        AtomicInteger calls = new AtomicInteger();
        ProviderProbeClient client = client(request -> {
            calls.incrementAndGet();
            return result(false, false, 1, 1);
        });

        assertThatThrownBy(() -> runner(registration, client).run(new ProviderGoldenGatePlan(
                registration,
                List.of(new ProviderGoldenCase(
                        "tool-call",
                        ModelUseCase.TOOL_CALL,
                        "Request the declared no-op tool.")),
                20,
                NOW.plusSeconds(60))))
                .isInstanceOf(ProviderGatewayException.class)
                .extracting("code")
                .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
        assertThat(calls).hasValue(1);
    }

    @Test
    void invalidStructuredOutputFailsClosed() {
        ProviderModelRegistration registration = registration(Set.of(
                ModelCapability.CHAT,
                ModelCapability.STRUCTURED_OUTPUT));
        ProviderProbeClient client = client(request -> result(true, false, 0, 0));

        assertThatThrownBy(() -> runner(registration, client).run(new ProviderGoldenGatePlan(
                registration,
                List.of(new ProviderGoldenCase(
                        "structured",
                        ModelUseCase.STRUCTURED_OUTPUT,
                        "Return the fixed schema.")),
                20,
                NOW.plusSeconds(60))))
                .isInstanceOf(ProviderGatewayException.class)
                .hasMessage("Provider structured output failed schema validation.");
    }

    @Test
    void rawProviderFailureContentIsNotPropagated() {
        String credentialProbe = "credential-probe-that-must-never-leak";
        ProviderModelRegistration registration = registration(Set.of(ModelCapability.CHAT));
        ProviderProbeClient client = client(request -> {
            throw new RuntimeException(credentialProbe);
        });

        assertThatThrownBy(() -> runner(registration, client).run(new ProviderGoldenGatePlan(
                registration,
                List.of(new ProviderGoldenCase("chat", ModelUseCase.CHAT, "Reply with OK.")),
                20,
                NOW.plusSeconds(60))))
                .isInstanceOf(ProviderGatewayException.class)
                .hasMessage("Model provider response failed validation.")
                .hasMessageNotContaining(credentialProbe);
    }

    @Test
    void liveGateContractsContainNoCredentialFields() {
        Stream<Class<?>> records = Stream.of(
                ProviderGoldenCase.class,
                ProviderGoldenGatePlan.class,
                ProviderProbeRequest.class,
                ProviderProbeResult.class,
                ProviderGoldenGateReport.class);

        assertThat(records
                .flatMap(type -> Stream.of(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .map(String::toLowerCase))
                .noneMatch(name -> name.contains("secret")
                        || name.contains("credential")
                        || name.endsWith("token")
                        || name.equals("key")
                        || name.endsWith("key"));
    }

    private static ProviderGoldenGateRunner runner(
            ProviderModelRegistration registration,
            ProviderProbeClient client) {
        ProviderCapabilityPolicy policy = ProviderCapabilityPolicy.stage2Baseline();
        ProviderCapabilityRegistry capabilities = new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                policy,
                List.of(registration));
        return new ProviderGoldenGateRunner(
                capabilities,
                new ProviderProbeClientRegistry(List.of(client)),
                new ProviderErrorNormalizer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProviderModelRegistration registration(Set<ModelCapability> capabilities) {
        return new ProviderModelRegistration(
                ModelProvider.OPENAI,
                "stage2-live-model",
                capabilities,
                Duration.ofSeconds(30),
                3);
    }

    private static ProviderProbeClient client(ProbeFunction function) {
        return new ProviderProbeClient() {
            @Override
            public ModelProvider provider() {
                return ModelProvider.OPENAI;
            }

            @Override
            public ProviderProbeResult probe(ProviderProbeRequest request) {
                return function.apply(request);
            }
        };
    }

    private static ProviderProbeResult result(
            boolean responsePresent,
            boolean structuredOutputSchemaValid,
            int toolCallCandidates,
            int toolExecutions) {
        return new ProviderProbeResult(
                responsePresent,
                structuredOutputSchemaValid,
                toolCallCandidates,
                toolExecutions,
                10,
                5,
                Duration.ofMillis(25));
    }

    @FunctionalInterface
    private interface ProbeFunction {
        ProviderProbeResult apply(ProviderProbeRequest request);
    }
}
