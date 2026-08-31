package org.urizo.axmodulestudio.backend.orchestration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.CapabilityRegistrationException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.Stage2ProviderModels;

@Service
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class ProfileModelBindingService {

    private static final Pattern BINDING_KEY =
            Pattern.compile("^[a-z][a-z0-9_-]{0,127}$");
    private static final Set<String> BINDING_FIELDS = Set.of("primary", "fallback");
    private static final Map<String, Map<String, ModelTarget>> BINDINGS = Map.of(
            "LLM_OPS", Map.of(
                    "llm-ops-analyze", new ModelTarget(
                            ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                    "llm-ops-code", new ModelTarget(
                            ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                    "llm-ops-review", new ModelTarget(
                            ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT)),
            "NATURAL_CMS", Map.of(
                    "natural-cms-analyze", new ModelTarget(
                            ModelProvider.OPENAI, Stage2ProviderModels.OPENAI_CHAT),
                    "natural-cms-command", new ModelTarget(
                            ModelProvider.GOOGLE_GENAI, Stage2ProviderModels.GOOGLE_GENAI_CHAT)));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProviderCapabilityRegistry capabilityRegistry;

    public ProfileModelBindingService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ProviderCapabilityRegistry capabilityRegistry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.capabilityRegistry = Objects.requireNonNull(
                capabilityRegistry, "capabilityRegistry is required");
    }

    public List<ProviderModelRegistration> resolve(
            UUID profileVersionId,
            String nodeId,
            String handlerKey,
            ModelUseCase useCase) {
        Objects.requireNonNull(profileVersionId, "profileVersionId is required");
        Objects.requireNonNull(useCase, "useCase is required");
        try {
            String encoded = jdbcTemplate.queryForObject(
                    """
                    SELECT snapshot_json::text
                    FROM app.ai_profile_version
                    WHERE profile_version_id = ?
                      AND status IN ('ACTIVE', 'INACTIVE')
                    """,
                    String.class,
                    profileVersionId);
            if (encoded == null) {
                throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
            }
            return resolve(
                    objectMapper.readTree(encoded), profileVersionId, nodeId, handlerKey, useCase);
        }
        catch (EmptyResultDataAccessException | JsonProcessingException failure) {
            throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
        }
        catch (DataAccessException failure) {
            throw invalidBinding(ModelGatewayErrorCode.INTERNAL_TRANSIENT_ERROR);
        }
    }

    List<ProviderModelRegistration> resolve(
            JsonNode snapshot,
            UUID profileVersionId,
            String nodeId,
            String handlerKey,
            ModelUseCase useCase) {
        if (snapshot == null
                || !snapshot.isObject()
                || !profileVersionId.toString().equals(
                        snapshot.path("profileVersionId").asText())
                || nodeId == null
                || handlerKey == null) {
            throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
        }
        String profileKey = snapshot.path("profileKey").asText("");
        Map<String, ModelTarget> catalog = BINDINGS.get(profileKey);
        if (catalog == null) {
            throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
        }

        JsonNode matchedNode = null;
        JsonNode nodes = snapshot.path("nodes");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                if (nodeId.equals(node.path("id").asText())) {
                    if (matchedNode != null) {
                        throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
                    }
                    matchedNode = node;
                }
            }
        }
        if (matchedNode == null
                || !"agent".equals(matchedNode.path("type").asText())
                || !handlerKey.equals(matchedNode.path("handlerKey").asText())) {
            throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
        }

        List<String> bindingKeys = bindingKeys(snapshot.path("modelBindings").path(nodeId));
        Map<ModelTarget, ProviderModelRegistration> selected = new LinkedHashMap<>();
        for (String bindingKey : bindingKeys) {
            ModelTarget target = catalog.get(bindingKey);
            if (target == null) {
                throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
            }
            ProviderModelRegistration registration;
            try {
                registration = capabilityRegistry.require(
                        target.provider(), target.modelId(), useCase);
            }
            catch (CapabilityRegistrationException failure) {
                throw invalidBinding(failure.code());
            }
            if (selected.putIfAbsent(target, registration) != null) {
                throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
            }
        }
        return List.copyOf(selected.values());
    }

    private static List<String> bindingKeys(JsonNode binding) {
        Set<String> fields = new HashSet<>();
        if (binding.isObject()) {
            binding.fieldNames().forEachRemaining(fields::add);
        }
        if (!binding.isObject() || !fields.equals(BINDING_FIELDS)) {
            throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
        }

        String primary = binding.path("primary").asText("");
        JsonNode fallback = binding.path("fallback");
        if (!BINDING_KEY.matcher(primary).matches() || !fallback.isArray()) {
            throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
        }

        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ordered.add(primary);
        seen.add(primary);
        for (JsonNode candidate : fallback) {
            if (!candidate.isTextual()
                    || !BINDING_KEY.matcher(candidate.textValue()).matches()
                    || !seen.add(candidate.textValue())) {
                throw invalidBinding(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
            }
            ordered.add(candidate.textValue());
        }
        return List.copyOf(ordered);
    }

    private static ProviderGatewayException invalidBinding(ModelGatewayErrorCode code) {
        return new ProviderGatewayException(
                code,
                "The job profile does not provide a supported model binding for this node.");
    }

    private record ModelTarget(ModelProvider provider, String modelId) { }
}
