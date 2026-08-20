package org.urizo.axmodulestudio.backend.auth.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Compatibility implementation for the existing pbkdf2-sha256 database format. */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int DEFAULT_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    public PasswordHasher(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive.");
        }
        this.iterations = iterations;
    }

    public String hash(char[] password) {
        if (password == null) {
            throw new IllegalArgumentException("password is required.");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return String.join("$", PREFIX, Integer.toString(iterations),
                    encoder.encodeToString(salt), encoder.encodeToString(derived));
        }
        finally {
            Arrays.fill(derived, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public boolean matches(char[] password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }
        String[] fields = storedHash.split("\\$");
        if (fields.length != 4 || !PREFIX.equals(fields[0])) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        int storedIterations;
        try {
            storedIterations = Integer.parseInt(fields[1]);
            salt = Base64.getUrlDecoder().decode(fields[2]);
            expected = Base64.getUrlDecoder().decode(fields[3]);
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
        if (storedIterations < 1 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = derive(password, salt, storedIterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        }
        finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public String absentAccountProbe() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return String.join("$", PREFIX, Integer.toString(iterations),
                encoder.encodeToString(new byte[SALT_BYTES]),
                encoder.encodeToString(new byte[KEY_BITS / Byte.SIZE]));
    }

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

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
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
