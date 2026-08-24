package org.urizo.axmodulestudio.backend.auth.security;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;

public final class JwtTestSupport {

    private JwtTestSupport() {
    }

    public static Bundle bundle(Clock clock) {
        byte[] keyMaterial = new byte[32];
        Arrays.fill(keyMaterial, (byte) 0x42);
        SecretKey key = new SecretKeySpec(keyMaterial, "HmacSHA256");
        Arrays.fill(keyMaterial, (byte) 0);

        JwtProperties properties = new JwtProperties(
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                "ax-module-studio-test",
                "axms-api",
                "axms-refresh",
                Path.of("unused-test-key"),
                false);
        SecurityConfig config = new SecurityConfig();
        JwtEncoder encoder = config.jwtEncoder(key);
        JwtDecoder accessDecoder = config.accessJwtDecoder(key, properties);
        JwtDecoder refreshDecoder = config.refreshJwtDecoder(key, properties);
        JwtTokenProvider provider = new JwtTokenProvider(
                encoder, refreshDecoder, properties, clock);
        return new Bundle(properties, provider, accessDecoder, refreshDecoder);
    }

    public record Bundle(
            JwtProperties properties,
            JwtTokenProvider provider,
            JwtDecoder accessDecoder,
            JwtDecoder refreshDecoder) {
    }
}
