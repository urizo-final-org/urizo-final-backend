package org.urizo.axmodulestudio.backend.dev.cms;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;

public final class ProviderSecretCrypto {

    private static final int MASTER_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final short KEY_VERSION = 1;
    private static final byte[] FINGERPRINT_CONTEXT =
            "AXMS:provider-secret:fingerprint:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey encryptionKey;
    private final SecretKey fingerprintKey;
    private final SecureRandom secureRandom;

    public ProviderSecretCrypto(byte[] masterKey) {
        this(masterKey, new SecureRandom());
    }

    ProviderSecretCrypto(byte[] masterKey, SecureRandom secureRandom) {
        Objects.requireNonNull(masterKey, "masterKey is required");
        if (masterKey.length != MASTER_KEY_BYTES) {
            throw new IllegalArgumentException("Local CMS master key must contain exactly 32 bytes.");
        }
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");

        byte[] keyCopy = masterKey.clone();
        try {
            this.encryptionKey = new SecretKeySpec(keyCopy, "AES");
            this.fingerprintKey = new SecretKeySpec(deriveFingerprintKey(keyCopy), "HmacSHA256");
        }
        finally {
            Arrays.fill(keyCopy, (byte) 0);
        }
    }

    public EncryptedSecret encrypt(ModelProvider provider, byte[] plaintext) {
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(plaintext, "plaintext is required");
        if (plaintext.length == 0) {
            throw new IllegalArgumentException("Credential cannot be empty.");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(provider.name().getBytes(StandardCharsets.US_ASCII));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedSecret(ciphertext, nonce, fingerprint(plaintext), KEY_VERSION);
        }
        catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local provider credential encryption failed.", failure);
        }
    }

    public byte[] decrypt(ModelProvider provider, EncryptedSecret encryptedSecret) {
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(encryptedSecret, "encryptedSecret is required");
        if (encryptedSecret.keyVersion() != KEY_VERSION) {
            throw new IllegalArgumentException("Unsupported local provider credential key version.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, encryptedSecret.nonce()));
            cipher.updateAAD(provider.name().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(encryptedSecret.ciphertext());
        }
        catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local provider credential decryption failed.", failure);
        }
    }

    private String fingerprint(byte[] plaintext) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(fingerprintKey);
        return "hmac-sha256:" + HexFormat.of().formatHex(mac.doFinal(plaintext));
    }

    private static byte[] deriveFingerprintKey(byte[] masterKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal(FINGERPRINT_CONTEXT);
        }
        catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local provider fingerprint key derivation failed.", failure);
        }
    }

    public record EncryptedSecret(byte[] ciphertext, byte[] nonce, String fingerprint, short keyVersion) {

        public EncryptedSecret {
            ciphertext = Objects.requireNonNull(ciphertext, "ciphertext is required").clone();
            nonce = Objects.requireNonNull(nonce, "nonce is required").clone();
            fingerprint = Objects.requireNonNull(fingerprint, "fingerprint is required");
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
