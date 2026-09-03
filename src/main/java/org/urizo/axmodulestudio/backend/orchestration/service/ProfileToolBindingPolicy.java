package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/** Runtime view of the additive per-node Tool binding contract. */
public final class ProfileToolBindingPolicy {

    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

    public enum Mode {
        MODEL_OPTIONAL,
        MODEL_REQUIRED,
        SYSTEM_REQUIRED
    }

    private final Set<String> profileAllowedTools;
    private final Map<String, Map<String, Mode>> bindings;
    private final boolean legacy;

    private ProfileToolBindingPolicy(
            Set<String> profileAllowedTools,
            Map<String, Map<String, Mode>> bindings,
            boolean legacy) {
        this.profileAllowedTools = Set.copyOf(profileAllowedTools);
        this.bindings = bindings;
        this.legacy = legacy;
    }

    public static ProfileToolBindingPolicy decode(
            JsonNode snapshot, Set<String> registeredTools) {
        if (snapshot == null || !snapshot.isObject()) {
            throw new IllegalArgumentException("snapshot must be an object");
        }
        JsonNode allowed = snapshot.path("toolPolicy").path("allowedTools");
        if (!allowed.isArray()) {
            throw new IllegalArgumentException("allowedTools must be an array");
        }
        Set<String> profileTools = new LinkedHashSet<>();
        for (JsonNode tool : allowed) {
            if (!tool.isTextual()
                    || !registeredTools.contains(tool.textValue())
                    || !profileTools.add(tool.textValue())) {
                throw new IllegalArgumentException("allowedTools is invalid");
            }
        }

        JsonNode rawBindings = snapshot.get("toolBindings");
        if (rawBindings == null) {
            return new ProfileToolBindingPolicy(profileTools, Map.of(), true);
        }
        if (!rawBindings.isObject()) {
            throw new IllegalArgumentException("toolBindings must be an object");
        }
        Map<String, Map<String, Mode>> nodeBindings = new LinkedHashMap<>();
        rawBindings.properties().forEach(nodeEntry -> {
            if (!NODE_ID.matcher(nodeEntry.getKey()).matches()
                    || !nodeEntry.getValue().isObject()) {
                throw new IllegalArgumentException("node Tool bindings must be objects");
            }
            Map<String, Mode> tools = new LinkedHashMap<>();
            nodeEntry.getValue().properties().forEach(toolEntry -> {
                if (!registeredTools.contains(toolEntry.getKey())
                        || !profileTools.contains(toolEntry.getKey())
                        || !toolEntry.getValue().isTextual()) {
                    throw new IllegalArgumentException("Tool binding is invalid");
                }
                Mode mode;
                try {
                    mode = Mode.valueOf(toolEntry.getValue().textValue());
                }
                catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Tool binding mode is invalid", failure);
                }
                tools.put(toolEntry.getKey(), mode);
            });
            nodeBindings.put(nodeEntry.getKey(), Collections.unmodifiableMap(tools));
        });
        return new ProfileToolBindingPolicy(
                profileTools, Collections.unmodifiableMap(nodeBindings), false);
    }

    public static ProfileToolBindingPolicy legacy(Set<String> profileAllowedTools) {
        return new ProfileToolBindingPolicy(profileAllowedTools, Map.of(), true);
    }

    public Set<String> profileAllowedTools() {
        return profileAllowedTools;
    }

    public Map<String, Map<String, Mode>> bindings() {
        return bindings;
    }

    public boolean legacy() {
        return legacy;
    }

    public Set<String> toolsForNode(String nodeId) {
        if (legacy) return profileAllowedTools;
        return Set.copyOf(bindings.getOrDefault(nodeId, Map.of()).keySet());
    }

    public Set<String> modelToolsForNode(String nodeId) {
        if (legacy) return profileAllowedTools;
        Set<String> result = new LinkedHashSet<>();
        bindings.getOrDefault(nodeId, Map.of()).forEach((tool, mode) -> {
            if (mode == Mode.MODEL_OPTIONAL || mode == Mode.MODEL_REQUIRED) {
                result.add(tool);
            }
        });
        return Set.copyOf(result);
    }

    public Set<String> systemToolsForNode(String nodeId) {
        if (legacy) return profileAllowedTools;
        Set<String> result = new LinkedHashSet<>();
        bindings.getOrDefault(nodeId, Map.of()).forEach((tool, mode) -> {
            if (mode == Mode.SYSTEM_REQUIRED) result.add(tool);
        });
        return Set.copyOf(result);
    }
}
