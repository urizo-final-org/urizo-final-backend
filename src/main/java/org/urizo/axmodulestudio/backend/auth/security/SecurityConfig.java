package org.urizo.axmodulestudio.backend.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    @Profile("local-full")
    SecretKey authJwtSigningKey(JwtProperties properties) throws IOException {
        String encoded = Files.readString(properties.signingKeyFile(), StandardCharsets.US_ASCII).trim();
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encoded);
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException("The auth JWT signing key file is not valid base64.", ex);
        }
        if (keyBytes.length < 32) {
            Arrays.fill(keyBytes, (byte) 0);
            throw new IllegalStateException("The auth JWT signing key must contain at least 32 bytes.");
        }
        try {
            return new SecretKeySpec(keyBytes, "HmacSHA256");
        }
        finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    @Bean
    @Profile("local-full")
    JwtEncoder jwtEncoder(SecretKey authJwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(authJwtSigningKey));
    }

    @Bean(name = "accessJwtDecoder")
    @Profile("local-full")
    JwtDecoder accessJwtDecoder(SecretKey key, JwtProperties properties) {
        return decoder(key, properties, properties.accessAudience(), JwtTokenProvider.ACCESS_TYPE);
    }

    @Bean(name = "refreshJwtDecoder")
    @Profile("local-full")
    JwtDecoder refreshJwtDecoder(SecretKey key, JwtProperties properties) {
        return decoder(key, properties, properties.refreshAudience(), JwtTokenProvider.REFRESH_TYPE);
    }

    @Bean
    @Profile("local-full")
    JwtTokenProvider jwtTokenProvider(
            JwtEncoder encoder,
            @Qualifier("refreshJwtDecoder") JwtDecoder refreshDecoder,
            JwtProperties properties,
            Clock clock) {
        return new JwtTokenProvider(encoder, refreshDecoder, properties, clock);
    }

    /**
     * This chain owns only browser/API authentication. Internal Coding routes do not match it and
     * therefore continue through their existing opaque service-credential guards unchanged.
     */
    @Bean
    @Profile("local-full & !dev-session")
    SecurityFilterChain productionSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("accessJwtDecoder") JwtDecoder accessDecoder,
            AuthService authService,
            ObjectMapper objectMapper) throws Exception {
        SecurityErrorWriter errors = new SecurityErrorWriter(objectMapper);
        AxmsJwtAuthenticationConverter converter = new AxmsJwtAuthenticationConverter(authService);

        http.securityMatcher("/api/**", "/internal/dev/provider-credentials/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/api/readiness",
                                "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/site/**").permitAll()
                        .requestMatchers("/api/auth/logout", "/api/auth/me").authenticated()
                        .requestMatchers("/api/admin/cms/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/cms/**")
                                .hasAnyRole("SUPER_ADMIN", "GENERAL_ADMIN")
                        .requestMatchers(
                                "/api/admin/ai/profile-versions",
                                "/api/admin/ai/profile-versions/**")
                                .hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/projects").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/projects/*/connectors",
                                "/api/connectors/*/preview",
                                "/api/connectors/*/versions/*/activate",
                                "/api/connectors/*/sync",
                                "/api/knowledge-bases/*/versions",
                                "/api/knowledge-versions/*/activate",
                                "/api/knowledge-bases/*/rollback").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/internal/dev/provider-credentials").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT,
                                "/internal/dev/provider-credentials/*").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/internal/dev/provider-credentials/*/test").hasRole("SUPER_ADMIN")
                        .anyRequest().hasAnyRole("SUPER_ADMIN", "GENERAL_ADMIN"))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(accessDecoder)
                                .jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, failure) -> errors.write(
                                request, response, 401, "AUTHENTICATION_REQUIRED",
                                "A valid administrator session is required.")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> errors.write(
                                request, response, 401, "AUTHENTICATION_REQUIRED",
                                "A valid administrator session is required."))
                        .accessDeniedHandler((request, response, failure) -> errors.write(
                                request, response, 403, "FORBIDDEN",
                                "This function is not available for the current role.")));
        return http.build();
    }

    /** Lets the existing dev-session filter remain the sole compatibility authority. */
    @Bean
    @Profile("!local-full | dev-session")
    SecurityFilterChain compatibilitySecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static JwtDecoder decoder(
            SecretKey key, JwtProperties properties, String audience, String type) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2Error invalid = new OAuth2Error("invalid_token", "JWT claims are invalid.", null);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(invalid);
        OAuth2TokenValidator<Jwt> typeValidator = jwt -> type.equals(
                jwt.getClaimAsString(JwtTokenProvider.TOKEN_TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(invalid);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audienceValidator,
                typeValidator));
        return decoder;
    }
}
