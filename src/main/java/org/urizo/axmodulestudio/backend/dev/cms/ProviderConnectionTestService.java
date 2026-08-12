package org.urizo.axmodulestudio.backend.dev.cms;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.ai.gateway.Stage2ProviderModels;
import org.urizo.axmodulestudio.backend.dev.cms.ProviderHttpTransport.ProviderHttpResponse;

@Service
@Profile("dev")
public class ProviderConnectionTestService {

    static final String OPENAI_MODEL = Stage2ProviderModels.OPENAI_CHAT;
    static final String GOOGLE_MODEL = Stage2ProviderModels.GOOGLE_GENAI_CHAT;
    static final String ANTHROPIC_MODEL_PROBE = Stage2ProviderModels.ANTHROPIC_AUTH_PROBE;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String FIXED_PROMPT = "Reply with exactly OK.";

    private final ProviderCredentialResolver credentialResolver;
    private final LocalProviderSecretService secretService;
    private final EncryptedProviderSecretRepository repository;
    private final ProviderHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProviderConnectionTestService(
            ProviderCredentialResolver credentialResolver,
            LocalProviderSecretService secretService,
            EncryptedProviderSecretRepository repository,
            ProviderHttpTransport transport,
            ObjectMapper objectMapper,
            Clock clock) {
        this.credentialResolver = credentialResolver;
        this.secretService = secretService;
        this.repository = repository;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ProviderConnectionTestResult test(ModelProvider provider) {
        Instant startedAt = clock.instant();
        String modelId = modelId(provider);
        try (ProviderCredentialLease credential = credentialResolver.resolve(provider)) {
            byte[] credentialBytes = credential.copySecret();
            try {
                String credentialHeader = new String(credentialBytes, StandardCharsets.US_ASCII);
                ProviderHttpResponse response = switch (provider) {
                    case OPENAI -> callOpenAi(credentialHeader);
                    case GOOGLE_GENAI -> callGoogle(credentialHeader);
                    case ANTHROPIC -> callAnthropicModels(credentialHeader);
                    default -> throw new IllegalArgumentException(
                            "Provider is not supported by the local connection test.");
                };
                return complete(provider, modelId, response, startedAt);
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return recordFailure(provider, modelId, ProviderCredentialState.PROVIDER_UNAVAILABLE,
                        "INTERRUPTED", startedAt);
            }
            catch (IOException failure) {
                return recordFailure(provider, modelId, ProviderCredentialState.PROVIDER_UNAVAILABLE,
                        "NETWORK_ERROR", startedAt);
            }
            finally {
                Arrays.fill(credentialBytes, (byte) 0);
            }
        }
    }

    private ProviderHttpResponse callOpenAi(String credential) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", OPENAI_MODEL);
        body.put("max_completion_tokens", 16);
        body.put("reasoning_effort", "none");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", FIXED_PROMPT);
        return transport.exchange(
                "POST",
                URI.create("https://api.openai.com/v1/chat/completions"),
                Map.of(
                        "Authorization", "Bearer " + credential,
                        "Content-Type", "application/json"),
                writeJson(body),
                REQUEST_TIMEOUT);
    }

    private ProviderHttpResponse callGoogle(String credential) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", FIXED_PROMPT);
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", 8);
        return transport.exchange(
                "POST",
                URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                        + GOOGLE_MODEL + ":generateContent"),
                Map.of(
                        "x-goog-api-key", credential,
                        "Content-Type", "application/json"),
                writeJson(body),
                REQUEST_TIMEOUT);
    }

    private ProviderHttpResponse callAnthropicModels(String credential) throws IOException, InterruptedException {
        return transport.exchange(
                "GET",
                URI.create("https://api.anthropic.com/v1/models?limit=1"),
                Map.of(
                        "x-api-key", credential,
                        "anthropic-version", "2023-06-01"),
                "",
                REQUEST_TIMEOUT);
    }

    private ProviderConnectionTestResult complete(
            ModelProvider provider,
            String modelId,
            ProviderHttpResponse response,
            Instant startedAt) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            FailureClassification classification = classifyFailure(response);
            return recordFailure(provider, modelId, classification.state(), classification.safeCode(), startedAt);
        }
        try {
            ParsedSuccess parsed = parseSuccess(provider, response.body());
            return record(provider, modelId, ProviderCredentialState.VERIFIED, parsed.inferenceExecuted(),
                    parsed.inputTokens(), parsed.outputTokens(), "OK", startedAt);
        }
        catch (RuntimeException failure) {
            return recordFailure(provider, modelId, ProviderCredentialState.PROVIDER_UNAVAILABLE,
                    "MODEL_RESPONSE_INVALID", startedAt);
        }
    }

    private ParsedSuccess parseSuccess(ModelProvider provider, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return switch (provider) {
                case OPENAI -> {
                    String output = root.path("choices").path(0).path("message").path("content").asText("");
                    requireOutput(output);
                    yield new ParsedSuccess(
                            true,
                            nullableInt(root.path("usage").path("prompt_tokens")),
                            nullableInt(root.path("usage").path("completion_tokens")));
                }
                case GOOGLE_GENAI -> {
                    String output = root.path("candidates").path(0).path("content").path("parts").path(0)
                            .path("text").asText("");
                    requireOutput(output);
                    yield new ParsedSuccess(
                            true,
                            nullableInt(root.path("usageMetadata").path("promptTokenCount")),
                            nullableInt(root.path("usageMetadata").path("candidatesTokenCount")));
                }
                case ANTHROPIC -> {
                    if (!root.path("data").isArray() || root.path("data").isEmpty()) {
                        throw new IllegalArgumentException("Anthropic Models API response was empty.");
                    }
                    yield new ParsedSuccess(false, null, null);
                }
                default -> throw new IllegalArgumentException("Unsupported provider response.");
            };
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Provider response JSON was invalid.");
        }
    }

    private FailureClassification classifyFailure(ProviderHttpResponse response) {
        String providerCode = safeProviderCode(response.body());
        if (response.statusCode() == 401 || "API_KEY_INVALID".equals(providerCode)
                || "UNAUTHENTICATED".equals(providerCode)) {
            return new FailureClassification(ProviderCredentialState.INVALID_CREDENTIAL, "AUTHENTICATION_FAILED");
        }
        if (response.statusCode() == 402 || "BILLING_ERROR".equals(providerCode)
                || "INSUFFICIENT_QUOTA".equals(providerCode)) {
            return new FailureClassification(ProviderCredentialState.BILLING_BLOCKED, "BILLING_BLOCKED");
        }
        if (response.statusCode() == 429) {
            return new FailureClassification(ProviderCredentialState.PROVIDER_UNAVAILABLE, "RATE_LIMITED");
        }
        if (response.statusCode() >= 500) {
            return new FailureClassification(ProviderCredentialState.PROVIDER_UNAVAILABLE, "PROVIDER_UNAVAILABLE");
        }
        return new FailureClassification(
                ProviderCredentialState.PROVIDER_UNAVAILABLE,
                providerCode == null ? "HTTP_" + response.statusCode() : providerCode);
    }

    private String safeProviderCode(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String[] candidates = {
                    root.path("error").path("code").asText(null),
                    root.path("error").path("status").asText(null),
                    root.path("error").path("type").asText(null)
            };
            for (String candidate : candidates) {
                if (candidate != null) {
                    String normalized = candidate.toUpperCase(Locale.ROOT).replace('-', '_');
                    if (normalized.matches("[A-Z0-9_]{1,64}")) {
                        return normalized;
                    }
                }
            }
        }
        catch (JsonProcessingException ignored) {
            // Raw provider bodies are intentionally neither logged nor persisted.
        }
        return null;
    }

    private ProviderConnectionTestResult recordFailure(
            ModelProvider provider,
            String modelId,
            ProviderCredentialState state,
            String safeCode,
            Instant startedAt) {
        return record(provider, modelId, state, provider != ModelProvider.ANTHROPIC,
                null, null, safeCode, startedAt);
    }

    private ProviderConnectionTestResult record(
            ModelProvider provider,
            String modelId,
            ProviderCredentialState state,
            boolean inferenceExecuted,
            Integer inputTokens,
            Integer outputTokens,
            String safeCode,
            Instant startedAt) {
        Instant completedAt = clock.instant();
        long latencyMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        secretService.updateState(provider, state);
        repository.recordAudit(
                provider,
                modelId,
                state == ProviderCredentialState.VERIFIED ? "PASSED"
                        : state == ProviderCredentialState.BILLING_BLOCKED ? "BILLING_BLOCKED" : "FAILED",
                "OK".equals(safeCode) ? null : safeCode,
                inputTokens,
                outputTokens,
                latencyMs);
        return new ProviderConnectionTestResult(
                provider,
                modelId,
                state,
                inferenceExecuted,
                inputTokens,
                outputTokens,
                latencyMs,
                completedAt,
                safeCode);
    }

    private String writeJson(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Provider request JSON serialization failed.");
        }
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isIntegralNumber() ? node.intValue() : null;
    }

    private static void requireOutput(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("Provider output was empty.");
        }
    }

    private static String modelId(ModelProvider provider) {
        return switch (provider) {
            case OPENAI -> OPENAI_MODEL;
            case GOOGLE_GENAI -> GOOGLE_MODEL;
            case ANTHROPIC -> ANTHROPIC_MODEL_PROBE;
            default -> throw new IllegalArgumentException("Provider is not supported by the local connection test.");
        };
    }

    private record ParsedSuccess(boolean inferenceExecuted, Integer inputTokens, Integer outputTokens) {
    }

    private record FailureClassification(ProviderCredentialState state, String safeCode) {
    }
}
