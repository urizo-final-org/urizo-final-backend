package org.urizo.axmodulestudio.backend.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    void keepsTheVersionedPbkdf2FormatAndVerifiesRowsWrittenAtAnotherCost() {
        String legacy = new PasswordHasher(1_000).hash("kept-password".toCharArray());
        String raised = new PasswordHasher(2_000).hash("kept-password".toCharArray());

        assertThat(legacy)
                .matches("^pbkdf2-sha256\\$1000\\$[A-Za-z0-9_-]+\\$[A-Za-z0-9_-]+$");
        assertThat(new PasswordHasher(2_000).matches(
                "kept-password".toCharArray(), legacy)).isTrue();
        assertThat(new PasswordHasher(1_000).matches(
                "kept-password".toCharArray(), raised)).isTrue();
        assertThat(hasher.matches("wrong-password".toCharArray(), legacy)).isFalse();
    }

    @Test
    void saltsEveryPasswordAndFailsClosedForForeignFormats() {
        String first = hasher.hash("same-password".toCharArray());
        String second = hasher.hash("same-password".toCharArray());

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("same-password");
        assertThat(hasher.matches("same-password".toCharArray(), "bcrypt$10$abc$def"))
                .isFalse();
        assertThat(hasher.matches("same-password".toCharArray(),
                "pbkdf2-sha256$bad$c2FsdA$aGFzaA")).isFalse();
    }

    @Test
    void createsOnlyADeterministicSha256DigestOfTheFullRefreshJwt() {
        String rawRefreshJwt = "header.payload.signature";
        String digest = hasher.digestToken(rawRefreshJwt);

        assertThat(digest).hasSize(43);
        assertThat(digest).matches("^[A-Za-z0-9_-]+$");
        assertThat(digest).isEqualTo(hasher.digestToken(rawRefreshJwt));
        assertThat(digest).isNotEqualTo(hasher.digestToken("header.payload.other"));
        assertThat(digest).doesNotContain(rawRefreshJwt);
    }

    @Test
    void rejectsInvalidCostAndAbsentRefreshValues() {
        assertThatThrownBy(() -> new PasswordHasher(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.digestToken(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
