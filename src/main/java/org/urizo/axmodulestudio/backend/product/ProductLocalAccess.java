package org.urizo.axmodulestudio.backend.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-full & dev-session")
final class ProductLocalAccess {

    private static final Duration LIFETIME = Duration.ofHours(24);

    private final Clock clock;
    private final String token;
    private final Instant expiresAt;

    ProductLocalAccess(Clock clock) {
        this.clock = clock;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        java.util.Arrays.fill(random, (byte) 0);
        this.expiresAt = Instant.now(clock).plus(LIFETIME);
    }

    ProductApiContract.ProductSessionResponse session() {
        return new ProductApiContract.ProductSessionResponse(
                ProductApiContract.SCHEMA_VERSION, token, "Bearer", expiresAt);
    }

    boolean accepts(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !Instant.now(clock).isBefore(expiresAt)) {
            return false;
        }
        byte[] expected = token.getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.US_ASCII);
        try {
            return MessageDigest.isEqual(expected, supplied);
        }
        finally {
            java.util.Arrays.fill(expected, (byte) 0);
            java.util.Arrays.fill(supplied, (byte) 0);
        }
    }
}
