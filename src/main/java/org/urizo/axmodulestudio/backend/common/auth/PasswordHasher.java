package org.urizo.axmodulestudio.backend.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Adaptive one-way password hashing built on the JDK PBKDF2 provider.
 *
 * <p>Stored form is {@code pbkdf2-sha256$<iterations>$<salt>$<hash>} so the iteration count can be
 * raised later without invalidating existing rows. Plaintext is never stored or logged, and
 * verification compares in constant time.
 *
 * <p>Both fields use the URL-safe base64 alphabet so the stored value never contains a character the
 * database check constraint rejects.
 */
public final class PasswordHasher {

    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final String PREFIX = "pbkdf2-sha256";

    /**
     * Approved minimum work factor for a deployed instance.
     *
     * <p>Lowering it weakens every password written afterwards while every test that injects its own
     * cost keeps passing, so {@code PasswordHasherTest} pins this value.
     */
    static final int MINIMUM_ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String FIELD_SEPARATOR = "$";

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher() {
        this(MINIMUM_ITERATIONS);
    }

    /**
     * Creates a hasher with an explicit work factor.
     *
     * <p>Only a test harness lowers the cost, and doing so never weakens verification: every stored
     * value records the cost it was produced with, so rows written before and after a cost increase
     * keep verifying against their own value.
     */
    PasswordHasher(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive.");
        }
        this.iterations = iterations;
    }

    /** Produces the stored representation of a new password. */
    public String hash(char[] password) {
        Objects.requireNonNull(password, "password is required.");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return String.join(
                    FIELD_SEPARATOR,
                    PREFIX,
                    Integer.toString(iterations),
                    encoder.encodeToString(salt),
                    encoder.encodeToString(derived));
        }
        finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /**
     * Verifies a presented password against a stored representation.
     *
     * <p>An unparsable or foreign stored value fails closed instead of raising.
     */
    public boolean matches(char[] password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }
        String[] fields = storedHash.split("\\" + FIELD_SEPARATOR);
        if (fields.length != 4 || !PREFIX.equals(fields[0].toLowerCase(Locale.ROOT))) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(fields[1]);
            Base64.Decoder decoder = Base64.getUrlDecoder();
            salt = decoder.decode(fields[2]);
            expected = decoder.decode(fields[3]);
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
        if (iterations < 1 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = derive(password, salt, iterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        }
        finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    /**
     * A stored value that no password matches, produced at this instance's work factor.
     *
     * <p>Verifying against it costs the same as verifying a real account, so an unknown login id
     * cannot be separated from a wrong password by response time. The value is not a credential and
     * is never persisted.
     */
    String absentAccountProbe() {
        byte[] placeholder = new byte[KEY_BITS / Byte.SIZE];
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return String.join(
                FIELD_SEPARATOR,
                PREFIX,
                Integer.toString(iterations),
                encoder.encodeToString(new byte[SALT_BYTES]),
                encoder.encodeToString(placeholder));
    }

    /** Digests an opaque session token so the presented value is never stored. */
    public String digestToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            }
            finally {
                Arrays.fill(digest, (byte) 0);
            }
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        }
        catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2 derivation is unavailable.", ex);
        }
        finally {
            spec.clearPassword();
        }
    }
}
