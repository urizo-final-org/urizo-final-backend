package org.urizo.axmodulestudio.backend.coding.dto;

import jakarta.validation.constraints.Positive;

/**
 * The guardrail rules that do not name a path.
 *
 * <p>Unlike the folder selection these cannot be judged before the model runs. A request that
 * sounds small can still produce a thousand lines, so they are checked against the finished
 * candidate.
 *
 * <p>Build and test success are not settings here. They are already required by the deterministic
 * preview checks, and repeating them as a toggle would let a stored value contradict a rule the
 * pipeline enforces unconditionally.
 */
public final class GuardrailRuleContract {

    private GuardrailRuleContract() { }

    /**
     * A {@code null} limit means no limit. It is deliberately not zero: zero would forbid every
     * change, and an unset number has to be distinguishable from a number somebody chose.
     */
    public record Rules(
            boolean allowNewDependency,
            @Positive Integer maxChangedFiles,
            @Positive Integer maxChangedLines) {

        /** What applies before an administrator has chosen anything, and to a job with no copy. */
        public static Rules unrestrictedSize() {
            return new Rules(false, null, null);
        }
    }
}
