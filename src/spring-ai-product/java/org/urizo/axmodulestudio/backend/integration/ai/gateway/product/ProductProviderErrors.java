package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ApiException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFailure;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFailureKind;

/** Classifies only provider HTTP/SDK errors; never retains their body, URL or cause. */
final class ProductProviderErrors implements ResponseErrorHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_BYTES = 65_536;
    private static final Set<String> REFUSALS = Set.of(
            "content_filter", "content_policy_violation", "safety", "blocked", "prohibited_content");
    private final ModelProvider provider;

    ProductProviderErrors(ModelProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(java.net.URI url, org.springframework.http.HttpMethod method,
            ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String type = "";
        String code = "";
        String message = "";
        byte[] body = response.getBody().readNBytes(MAX_ERROR_BYTES + 1);
        if (body.length <= MAX_ERROR_BYTES) {
            try {
                JsonNode error = JSON.readTree(body).path("error");
                type = error.path("type").asText("");
                code = error.path("code").asText("");
                message = error.path("message").asText("");
            } catch (IOException | RuntimeException ignored) {
                // Unknown bodies do not become request/configuration guesses.
            }
        }
        throw classify(provider, status, type, code, message,
                retryAfter(response.getHeaders().getFirst("Retry-After")));
    }

    static RuntimeException sanitizeGoogle(RuntimeException failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof ProviderFailure) return failure;
            if (current instanceof ApiException api) {
                return classify(ModelProvider.GOOGLE_GENAI, api.code(), api.status(), "",
                        api.message(), null);
            }
        }
        return failure;
    }

    static ProviderFailure classify(ModelProvider provider, int status, String type,
            String code, String message, Duration retryAfter) {
        String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String normalizedCode = code == null ? "" : code.toLowerCase(Locale.ROOT);
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        ProviderFailureKind kind;
        if (REFUSALS.contains(normalizedType) || REFUSALS.contains(normalizedCode)
                || text.contains("content policy") || text.contains("content filter")
                || text.contains("safety")
                || text.startsWith("your request was rejected as a result of our safety system")) {
            kind = ProviderFailureKind.INVALID_RESPONSE;
        } else if (status == 401) {
            kind = ProviderFailureKind.AUTHENTICATION;
        } else if (status == 402) {
            kind = ProviderFailureKind.BILLING;
        } else if (status == 400 && provider == ModelProvider.ANTHROPIC
                && normalizedType.equals("invalid_request_error")
                && (text.startsWith("your credit balance is too low")
                    || text.startsWith("you have reached your specified api usage limits"))) {
            kind = ProviderFailureKind.BILLING;
        } else if ((status == 400 || status == 403) && provider == ModelProvider.GOOGLE_GENAI
                && (text.startsWith("api key not valid.")
                    || text.startsWith("api key expired.")
                    || text.startsWith("your api key was reported as leaked."))) {
            kind = ProviderFailureKind.AUTHENTICATION;
        } else if (status == 403) {
            kind = ProviderFailureKind.MODEL_ACCESS;
        } else if (status == 404 && (normalizedCode.equals("model_not_found")
                || provider == ModelProvider.ANTHROPIC && normalizedType.equals("not_found_error")
                    && text.startsWith("model:")
                || provider == ModelProvider.GOOGLE_GENAI && text.startsWith("models/"))) {
            kind = ProviderFailureKind.MODEL_ACCESS;
        } else if (status == 429 && (normalizedCode.equals("insufficient_quota")
                || normalizedType.equals("insufficient_quota")
                || normalizedCode.equals("billing_hard_limit_reached"))) {
            kind = ProviderFailureKind.QUOTA;
        } else if (status == 429) {
            kind = ProviderFailureKind.RATE_LIMITED;
        } else if (status == 408 || status == 504) {
            kind = ProviderFailureKind.TIMEOUT;
        } else if (status >= 500 && status <= 599) {
            kind = ProviderFailureKind.UNAVAILABLE;
        } else {
            kind = ProviderFailureKind.INVALID_RESPONSE;
        }
        return new ProviderFailure(kind, retryAfter);
    }

    private static Duration retryAfter(String header) {
        if (header == null || !header.matches("[0-9]{1,4}")) return null;
        long seconds = Long.parseLong(header);
        return seconds > 0 && seconds <= 3600 ? Duration.ofSeconds(seconds) : null;
    }
}
