package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

@Component
@Profile("dev")
public class LocalDevRequestGuard {

    static final String CSRF_HEADER = "X-AXMS-CSRF";

    private final String csrfToken;
    private final String trustedProxyHost;

    @Autowired
    public LocalDevRequestGuard(Environment environment) {
        this(generateToken(), environment.getProperty(
                "ax.local-provider-secrets.trusted-proxy-host", ""));
    }

    private static String generateToken() {
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        }
        finally {
            java.util.Arrays.fill(tokenBytes, (byte) 0);
        }
    }

    LocalDevRequestGuard(String csrfToken) {
        this(csrfToken, "");
    }

    LocalDevRequestGuard(String csrfToken, String trustedProxyHost) {
        this.csrfToken = csrfToken;
        this.trustedProxyHost = trustedProxyHost == null ? "" : trustedProxyHost.trim();
    }

    public String csrfToken(HttpServletRequest request) {
        requireLoopback(request);
        return csrfToken;
    }

    public void requireRead(HttpServletRequest request) {
        requireLoopback(request);
    }

    public void requireMutation(HttpServletRequest request) {
        requireLoopback(request);
        requireLoopbackOrigin(request.getHeader("Origin"));
        String supplied = request.getHeader(CSRF_HEADER);
        if (supplied == null || !MessageDigest.isEqual(
                csrfToken.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Local CMS CSRF validation failed.");
        }
    }

    private void requireLoopback(HttpServletRequest request) {
        try {
            InetAddress remote = InetAddress.getByName(request.getRemoteAddr());
            if (remote.isLoopbackAddress()) {
                return;
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            boolean forwardedLoopback = forwarded != null
                    && !forwarded.contains(",")
                    && InetAddress.getByName(forwarded.trim()).isLoopbackAddress();
            boolean trustedProxy = !trustedProxyHost.isEmpty()
                    && java.util.Arrays.stream(InetAddress.getAllByName(trustedProxyHost))
                            .anyMatch(remote::equals);
            if (forwardedLoopback && trustedProxy) {
                return;
            }
            throw new SecurityException("Local CMS accepts loopback requests only.");
        }
        catch (java.net.UnknownHostException failure) {
            throw new SecurityException("Local CMS request address is invalid.");
        }
    }

    private static void requireLoopbackOrigin(String origin) {
        try {
            URI parsed = URI.create(origin == null ? "" : origin);
            if (!"http".equalsIgnoreCase(parsed.getScheme())
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || !InetAddress.getByName(parsed.getHost()).isLoopbackAddress()) {
                throw new SecurityException("Local CMS mutation origin is not allowed.");
            }
        }
        catch (IllegalArgumentException | java.net.UnknownHostException failure) {
            throw new SecurityException("Local CMS mutation origin is invalid.");
        }
    }
}
