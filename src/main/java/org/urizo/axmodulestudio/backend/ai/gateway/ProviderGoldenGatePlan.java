package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ProviderGoldenGatePlan(
        ProviderModelRegistration registration,
        List<ProviderGoldenCase> cases,
        int repetitions,
        Instant deadline) {

    public static final int MIN_REPETITIONS = 20;
    private static final int MAX_REPETITIONS = 100;
    private static final int MAX_TOTAL_REQUESTS = 500;

    public ProviderGoldenGatePlan {
        registration = Objects.requireNonNull(registration, "registration is required");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases are required"));
        deadline = Objects.requireNonNull(deadline, "deadline is required");

        if (cases.isEmpty()) {
            throw new IllegalArgumentException("at least one fixed golden case is required");
        }
        if (repetitions < MIN_REPETITIONS || repetitions > MAX_REPETITIONS) {
            throw new IllegalArgumentException("repetitions must be between 20 and 100");
        }
        if ((long) cases.size() * repetitions > MAX_TOTAL_REQUESTS) {
            throw new IllegalArgumentException("golden plan exceeds the 500 request safety limit");
        }

        Set<String> caseIds = new HashSet<>();
        for (ProviderGoldenCase goldenCase : cases) {
            if (!caseIds.add(goldenCase.caseId())) {
                throw new IllegalArgumentException("duplicate golden caseId");
            }
            if (!registration.capabilities().containsAll(goldenCase.useCase().requiredCapabilities())) {
                throw new CapabilityRegistrationException(
                        ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                        "golden case requires an unregistered model capability");
            }
        }
    }

    public int plannedRequests() {
        return Math.multiplyExact(cases.size(), repetitions);
    }
}
