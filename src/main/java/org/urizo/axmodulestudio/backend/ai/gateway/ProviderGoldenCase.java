package org.urizo.axmodulestudio.backend.ai.gateway;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProviderGoldenCase(
        String caseId,
        ModelUseCase useCase,
        String fixedPrompt) {

    private static final Pattern CASE_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final int MAX_PROMPT_LENGTH = 4_000;

    public ProviderGoldenCase {
        caseId = Objects.requireNonNull(caseId, "caseId is required");
        useCase = Objects.requireNonNull(useCase, "useCase is required");
        fixedPrompt = Objects.requireNonNull(fixedPrompt, "fixedPrompt is required");

        if (!CASE_ID.matcher(caseId).matches()) {
            throw new IllegalArgumentException("caseId has an invalid format");
        }
        if (fixedPrompt.isBlank() || fixedPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("fixedPrompt must contain between 1 and 4000 characters");
        }
        if (useCase == ModelUseCase.EMBEDDING) {
            throw new IllegalArgumentException("chat provider golden cases cannot use EMBEDDING");
        }
    }
}
