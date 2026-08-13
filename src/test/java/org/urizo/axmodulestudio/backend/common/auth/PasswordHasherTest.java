package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    /** Behavior is independent of the work factor, so the suite runs at a cheap cost. */
    private static final int TEST_ITERATIONS = 1_000;

    private static final String PASSWORD_UNDER_TEST = "correct-horse-battery";

    private final PasswordHasher hasher = new PasswordHasher(TEST_ITERATIONS);

    @Test
    void acceptsTheOriginalPasswordAndRejectsEveryOtherValue() {
        String stored = hasher.hash("correct-horse-battery".toCharArray());

        assertThat(hasher.matches("correct-horse-battery".toCharArray(), stored)).isTrue();
        assertThat(hasher.matches("correct-horse-batterY".toCharArray(), stored)).isFalse();
        assertThat(hasher.matches("".toCharArray(), stored)).isFalse();
    }

    @Test
    void saltsEachHashSoIdenticalPasswordsNeverShareStoredValues() {
        String first = hasher.hash("same-password".toCharArray());
        String second = hasher.hash("same-password".toCharArray());

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("same-password".toCharArray(), first)).isTrue();
        assertThat(hasher.matches("same-password".toCharArray(), second)).isTrue();
    }

    @Test
    void recordsTheAlgorithmAndIterationCountSoTheCostCanBeRaisedLater() {
        String stored = hasher.hash("cost-visible".toCharArray());

        assertThat(stored.split("\\$")).hasSize(4);
        assertThat(stored).startsWith(PasswordHasher.PREFIX + "$" + TEST_ITERATIONS + "$");
        assertThat(stored).doesNotContain("cost-visible");
    }

    @Test
    void deploysAtTheApprovedMinimumWorkFactor() {
        assertThat(PasswordHasher.MINIMUM_ITERATIONS).isGreaterThanOrEqualTo(600_000);
        assertThat(new PasswordHasher().hash("deployed-cost".toCharArray()))
                .as("the default constructor is what a deployed instance uses")
                .startsWith(PasswordHasher.PREFIX + "$" + PasswordHasher.MINIMUM_ITERATIONS + "$");
    }

    @Test
    void verifiesHashesWrittenAtAnotherWorkFactorSoACostIncreaseKeepsExistingAccounts() {
        String legacy = new PasswordHasher(1_000).hash("kept-password".toCharArray());
        String raised = new PasswordHasher(2_000).hash("kept-password".toCharArray());

        assertThat(new PasswordHasher(2_000).matches("kept-password".toCharArray(), legacy)).isTrue();
        assertThat(new PasswordHasher(1_000).matches("kept-password".toCharArray(), raised)).isTrue();
    }

    @Test
    void rejectsANonPositiveWorkFactor() {
        assertThatThrownBy(() -> new PasswordHasher(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void probesAnAbsentAccountAtTheSameCostAsARealVerification() {
        assertThat(hasher.absentAccountProbe())
                .as("a cheaper probe would leak account existence through response time")
                .startsWith(PasswordHasher.PREFIX + "$" + TEST_ITERATIONS + "$");
        assertThat(new PasswordHasher().absentAccountProbe())
                .startsWith(PasswordHasher.PREFIX + "$" + PasswordHasher.MINIMUM_ITERATIONS + "$");
    }

    @Test
    void encodesWithTheUrlSafeAlphabetTheDatabaseCheckConstraintAccepts() {
        // ck_admin_account_password_hash allows [A-Za-z0-9_-] only, so a standard-alphabet
        // '+' or '/' would be rejected on insert for some random salts but not others.
        String pattern = "^pbkdf2-sha256\\$[1-9][0-9]*\\$[A-Za-z0-9_-]+\\$[A-Za-z0-9_-]+$";
        for (int attempt = 0; attempt < 200; attempt++) {
            assertThat(hasher.hash("alphabet-check".toCharArray())).matches(pattern);
        }
        assertThat(hasher.absentAccountProbe()).matches(pattern);
        assertThat(new PasswordHasher().absentAccountProbe()).matches(pattern);
    }

    @Test
    void keepsTheAbsentAccountProbeUnmatchableAndParsable() {
        String probe = hasher.absentAccountProbe();

        assertThat(probe.split("\\$")).hasSize(4);
        assertThat(hasher.matches("".toCharArray(), probe)).isFalse();
        assertThat(hasher.matches(PASSWORD_UNDER_TEST.toCharArray(), probe)).isFalse();
    }

    @Test
    void failsClosedForMissingUnparsableOrForeignStoredValues() {
        String stored = hasher.hash("value".toCharArray());

        assertThat(hasher.matches(null, stored)).isFalse();
        assertThat(hasher.matches("value".toCharArray(), null)).isFalse();
        assertThat(hasher.matches("value".toCharArray(), "")).isFalse();
        assertThat(hasher.matches("value".toCharArray(), "bcrypt$10$abc$def")).isFalse();
        assertThat(hasher.matches("value".toCharArray(), "pbkdf2-sha256$notanumber$c2FsdA$aGFzaA"))
                .isFalse();
        assertThat(hasher.matches("value".toCharArray(), "pbkdf2-sha256$1000$!!!$???")).isFalse();
    }

    @Test
    void digestsSessionTokensDeterministicallyWithoutKeepingThePresentedValue() {
        String digest = hasher.digestToken("opaque-session-token");

        assertThat(digest).isEqualTo(hasher.digestToken("opaque-session-token"));
        assertThat(digest).isNotEqualTo(hasher.digestToken("other-session-token"));
        assertThat(digest).doesNotContain("opaque-session-token");
    }

    @Test
    void rejectsAnAbsentSessionToken() {
        assertThatThrownBy(() -> hasher.digestToken(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
