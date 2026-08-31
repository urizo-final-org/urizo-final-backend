package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

final class ProfileSnapshotValidator {

    private static final Set<String> PROFILE_KEYS = Set.of("LLM_OPS", "NATURAL_CMS");
    private static final Set<String> NODE_TYPES =
            Set.of("start", "agent", "tool", "approval", "check", "guardrail", "end");
    private static final Set<String> EXECUTION_FIELDS = Set.of(
            "jobId", "pipelineAttempt", "executionAttempt", "stateVersion",
            "workspaceId", "toolCallId", "traceId");
    private static final Set<String> AUTHORING_FIELDS = Set.of(
            "nodes", "edges", "config", "modelBindings", "toolPolicy",
            "guardrailProfileKey");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "contractVersion", "profileVersionId", "profileKey", "profileVersion",
            "nodes", "edges", "config", "modelBindings", "toolPolicy",
            "guardrailProfileKey");
    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern HANDLER_KEY = Pattern.compile(
            "^[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)*$");
    private static final Pattern RESULT_PORT = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern MODEL_KEY = Pattern.compile("^[a-z][a-z0-9_-]{0,127}$");
    private static final Pattern TOOL_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,127}$");
    private static final Pattern GUARDRAIL_KEY = Pattern.compile("^[a-z][a-z0-9_.:-]{0,127}$");
    private static final int MAX_WORKER_ATTEMPTS = 20;
    private static final String SUPPORTED_GUARDRAIL_PROFILE = "central.default";

    private static final Map<String, Set<String>> TOOLS = Map.of(
            "LLM_OPS", Set.of(
                    "read_file", "search_code", "read_diff", "apply_patch", "run_check",
                    "check_package_allowlist", "scan_changed_files"),
            "NATURAL_CMS", Set.of(
                    "resolve_cms_target", "validate_cms_command", "create_cms_preview",
                    "discard_cms_preview", "revalidate_cms_preview", "apply_cms_preview"));

    private static final Map<String, HandlerContract> HANDLERS = Map.ofEntries(
            handler("common.start", "start", "next"),
            handler("common.guardrail", "guardrail", "passed", "failed"),
            handler("common.check", "check", "passed", "failed"),
            handler("common.approval", "approval", "approved"),
            handler("common.end", "end"),
            handler("coding.analyze", "agent", "feasible", "infeasible"),
            handler("coding.code", "agent", "completed"),
            handler("coding.review", "agent", "passed", "changes_requested"),
            handler("coding.preview", "tool", "ready"),
            handler("coding.approval", "approval", "approved"),
            handler("coding.preview_approval", "approval", "approved", "rejected"),
            handler("coding.pr_request", "tool", "requested"),
            handler("coding.deploy_request", "tool", "recorded"),
            handler("coding.rework_gate", "check", "retry", "handover"),
            handler("cms.analyze", "agent", "feasible", "infeasible"),
            handler("cms.preview", "agent", "ready"),
            handler("cms.discard", "tool", "retry", "discarded"),
            handler("cms.apply", "tool", "applied"),
            handler("cms.approval", "approval", "approved", "rejected"));

    private ProfileSnapshotValidator() { }

    static void validateAuthoring(String profileKey, JsonNode authoringSnapshot) {
        requireProfileKey(profileKey);
        ObjectNode authoring = object(authoringSnapshot, "snapshot");
        exactFields(authoring, AUTHORING_FIELDS, "snapshot");

        ObjectNode full = JsonNodeFactory.instance.objectNode();
        full.put("contractVersion", "1.0");
        full.put("profileVersionId", "11111111-1111-4111-8111-111111111111");
        full.put("profileKey", profileKey);
        full.put("profileVersion", 1);
        for (String field : AUTHORING_FIELDS) {
            full.set(field, authoring.get(field).deepCopy());
        }
        validate(full, UUID.fromString(full.path("profileVersionId").asText()), profileKey, 1);
    }

    static void validateStored(
            UUID profileVersionId, String profileKey, int profileVersion, JsonNode snapshot) {
        validate(snapshot, profileVersionId, profileKey, profileVersion);
    }

    private static void validate(
            JsonNode raw,
            UUID expectedProfileVersionId,
            String expectedProfileKey,
            int expectedProfileVersion) {
        requireProfileKey(expectedProfileKey);
        ObjectNode snapshot = object(raw, "snapshot");
        exactFields(snapshot, SNAPSHOT_FIELDS, "snapshot");
        if (!"1.0".equals(text(snapshot.get("contractVersion"), "snapshot.contractVersion"))) {
            invalid("snapshot.contractVersion is unsupported");
        }
        if (!expectedProfileVersionId.toString().equals(
                text(snapshot.get("profileVersionId"), "snapshot.profileVersionId"))) {
            invalid("snapshot.profileVersionId does not match the stored identity");
        }
        if (!expectedProfileKey.equals(text(snapshot.get("profileKey"), "snapshot.profileKey"))) {
            invalid("snapshot.profileKey does not match the stored identity");
        }
        if (positiveInt(snapshot.get("profileVersion"), "snapshot.profileVersion")
                != expectedProfileVersion) {
            invalid("snapshot.profileVersion does not match the stored identity");
        }

        List<Node> nodes = nodes(snapshot.get("nodes"), expectedProfileKey);
        List<Edge> edges = edges(snapshot.get("edges"));
        SnapshotConfig config = config(snapshot.get("config"));
        ObjectNode modelBindings = object(snapshot.get("modelBindings"), "snapshot.modelBindings");
        rejectExecutionFields(modelBindings);
        validateModelBindings(modelBindings);
        ObjectNode toolPolicy = object(snapshot.get("toolPolicy"), "snapshot.toolPolicy");
        rejectExecutionFields(toolPolicy);
        validateToolPolicy(toolPolicy, expectedProfileKey);
        if (!SUPPORTED_GUARDRAIL_PROFILE.equals(textMatching(
                snapshot.get("guardrailProfileKey"), GUARDRAIL_KEY,
                "snapshot.guardrailProfileKey"))) {
            invalid("snapshot.guardrailProfileKey is not supported by this runtime");
        }
        validateGraph(nodes, edges, config, modelBindings);
    }

    private static void validateModelBindings(ObjectNode bindings) {
        bindings.fields().forEachRemaining(entry -> {
            ObjectNode binding = object(entry.getValue(),
                    "snapshot.modelBindings." + entry.getKey());
            exactFields(binding, Set.of("primary", "fallback"),
                    "snapshot.modelBindings." + entry.getKey());
            String primary = textMatching(binding.get("primary"), MODEL_KEY,
                    "snapshot.modelBindings." + entry.getKey() + ".primary");
            Set<String> fallback = stringSet(binding.get("fallback"), MODEL_KEY,
                    "snapshot.modelBindings." + entry.getKey() + ".fallback");
            if (fallback.contains(primary)) {
                invalid("snapshot.modelBindings fallback must not repeat the primary model");
            }
        });
    }

    private static void validateToolPolicy(ObjectNode toolPolicy, String profileKey) {
        exactFields(toolPolicy, Set.of("allowedTools"), "snapshot.toolPolicy");
        Set<String> allowed = stringSet(
                toolPolicy.get("allowedTools"), TOOL_KEY, "snapshot.toolPolicy.allowedTools");
        if (!TOOLS.get(profileKey).containsAll(allowed)) {
            invalid("snapshot.toolPolicy contains an unregistered Tool");
        }
    }

    private static List<Node> nodes(JsonNode raw, String profileKey) {
        if (raw == null || !raw.isArray() || raw.isEmpty()) {
            invalid("snapshot.nodes must be a non-empty array");
        }
        List<Node> result = new ArrayList<>();
        for (JsonNode item : raw) {
            ObjectNode node = object(item, "node");
            exactFields(node, Set.of("id", "type", "handlerKey", "resultPorts", "config"), "node");
            String id = textMatching(node.get("id"), NODE_ID, "node.id");
            String type = text(node.get("type"), "node.type");
            if (!NODE_TYPES.contains(type)) {
                invalid("node.type is unsupported");
            }
            String handlerKey = textMatching(node.get("handlerKey"), HANDLER_KEY, "node.handlerKey");
            HandlerContract contract = HANDLERS.get(handlerKey);
            String featurePrefix = "LLM_OPS".equals(profileKey) ? "coding." : "cms.";
            if (contract == null
                    || (!handlerKey.startsWith("common.") && !handlerKey.startsWith(featurePrefix))
                    || !contract.nodeType().equals(type)) {
                invalid("node.handlerKey is not registered for this Profile");
            }
            Set<String> ports = stringSet(node.get("resultPorts"), RESULT_PORT, "node.resultPorts");
            if (!ports.equals(contract.resultPorts())) {
                invalid("node.resultPorts do not match the registered Handler");
            }
            ObjectNode nodeConfig = object(node.get("config"), "node.config");
            rejectExecutionFields(nodeConfig);
            if ("guardrail".equals(type)
                    && (nodeConfig.size() != 1 || !nodeConfig.path("locked").isBoolean()
                    || !nodeConfig.path("locked").booleanValue())) {
                invalid("snapshot requires locked guardrail nodes");
            }
            result.add(new Node(id, type, ports, nodeConfig.deepCopy()));
        }
        return result;
    }

    private static List<Edge> edges(JsonNode raw) {
        if (raw == null || !raw.isArray()) {
            invalid("snapshot.edges must be an array");
        }
        List<Edge> result = new ArrayList<>();
        for (JsonNode item : raw) {
            ObjectNode edge = object(item, "edge");
            exactFields(edge, Set.of("from", "resultPort", "to"), "edge");
            result.add(new Edge(
                    textMatching(edge.get("from"), NODE_ID, "edge.from"),
                    textMatching(edge.get("resultPort"), RESULT_PORT, "edge.resultPort"),
                    textMatching(edge.get("to"), NODE_ID, "edge.to")));
        }
        return result;
    }

    private static SnapshotConfig config(JsonNode raw) {
        ObjectNode config = object(raw, "config");
        exactFields(config, Set.of("maxNodes", "maxAttempts", "loopLimits"), "config");
        int maxNodes = positiveInt(config.get("maxNodes"), "config.maxNodes");
        int maxAttempts = positiveInt(config.get("maxAttempts"), "config.maxAttempts");
        if (maxAttempts > MAX_WORKER_ATTEMPTS) {
            invalid("config.maxAttempts must be between 1 and 20");
        }
        JsonNode rawLimits = config.get("loopLimits");
        if (rawLimits == null || !rawLimits.isArray()) {
            invalid("config.loopLimits must be an array");
        }
        Set<Route> limits = new HashSet<>();
        for (JsonNode item : rawLimits) {
            ObjectNode limit = object(item, "config.loopLimits[]");
            exactFields(limit, Set.of("from", "resultPort", "to", "maxIterations"),
                    "config.loopLimits[]");
            Route route = new Route(
                    textMatching(limit.get("from"), NODE_ID, "config.loopLimits[].from"),
                    textMatching(limit.get("resultPort"), RESULT_PORT,
                            "config.loopLimits[].resultPort"),
                    textMatching(limit.get("to"), NODE_ID, "config.loopLimits[].to"));
            positiveInt(limit.get("maxIterations"), "config.loopLimits[].maxIterations");
            if (!limits.add(route)) {
                invalid("config.loopLimits contains duplicate routes");
            }
        }
        return new SnapshotConfig(maxNodes, maxAttempts, limits);
    }

    private static void validateGraph(
            List<Node> nodes,
            List<Edge> edges,
            SnapshotConfig config,
            ObjectNode modelBindings) {
        Map<String, Node> byId = new HashMap<>();
        for (Node node : nodes) {
            if (byId.put(node.id(), node) != null) {
                invalid("snapshot.nodes contains duplicate ids");
            }
        }
        if (nodes.size() > config.maxNodes()) {
            invalid("snapshot.nodes exceeds config.maxNodes");
        }

        List<Node> starts = nodes.stream().filter(node -> "start".equals(node.type())).toList();
        List<Node> ends = nodes.stream().filter(node -> "end".equals(node.type())).toList();
        if (starts.size() != 1 || ends.size() != 1) {
            invalid("snapshot must contain exactly one start and one end node");
        }
        Node start = starts.get(0);
        Node end = ends.get(0);
        if (!end.resultPorts().isEmpty()) {
            invalid("snapshot end node must not declare result ports");
        }
        Set<String> guardrails = new HashSet<>();
        Set<String> agents = new HashSet<>();
        for (Node node : nodes) {
            if ("guardrail".equals(node.type())) guardrails.add(node.id());
            if ("agent".equals(node.type())) agents.add(node.id());
        }
        if (guardrails.isEmpty()) {
            invalid("snapshot requires locked guardrail nodes");
        }
        if (!fields(modelBindings).equals(agents)) {
            invalid("snapshot.modelBindings must match all agent nodes");
        }

        Map<String, Set<String>> adjacency = emptyAdjacency(byId.keySet());
        Map<String, Set<String>> reverse = emptyAdjacency(byId.keySet());
        Set<PortRoute> routes = new HashSet<>();
        Set<Route> edgeRoutes = new HashSet<>();
        for (Edge edge : edges) {
            Node source = byId.get(edge.from());
            Node target = byId.get(edge.to());
            if (source == null || target == null) {
                invalid("snapshot.edges references an unknown node");
            }
            if (edge.to().equals(start.id()) || edge.from().equals(end.id())) {
                invalid("snapshot start/end edge direction is invalid");
            }
            if (!source.resultPorts().contains(edge.resultPort())) {
                invalid("snapshot.edges references an undeclared result port");
            }
            if (!routes.add(new PortRoute(edge.from(), edge.resultPort()))) {
                invalid("snapshot.edges contains duplicate result routes");
            }
            edgeRoutes.add(edge.route());
            adjacency.get(edge.from()).add(edge.to());
            reverse.get(edge.to()).add(edge.from());
        }

        Set<PortRoute> declared = new HashSet<>();
        for (Node node : nodes) {
            for (String port : node.resultPorts()) declared.add(new PortRoute(node.id(), port));
        }
        if (!routes.equals(declared)) {
            invalid("snapshot result ports must each have exactly one edge");
        }
        if (!reachable(start.id(), adjacency).equals(byId.keySet())) {
            invalid("snapshot contains nodes unreachable from start");
        }
        if (!reachable(end.id(), reverse).equals(byId.keySet())) {
            invalid("snapshot contains nodes that cannot reach end");
        }

        Map<String, Set<String>> withoutGuardrails = emptyAdjacency(byId.keySet());
        for (Map.Entry<String, Set<String>> entry : adjacency.entrySet()) {
            if (guardrails.contains(entry.getKey())) continue;
            for (String target : entry.getValue()) {
                if (!guardrails.contains(target)) withoutGuardrails.get(entry.getKey()).add(target);
            }
        }
        if (reachable(start.id(), withoutGuardrails).contains(end.id())) {
            invalid("snapshot contains a guardrail bypass path");
        }

        if (!edgeRoutes.containsAll(config.loopLimits())) {
            invalid("config.loopLimits references an unknown edge");
        }
        for (Route limit : config.loopLimits()) {
            Map<String, Set<String>> withoutLimit = adjacencyWithout(byId.keySet(), edges, Set.of(limit));
            if (!limit.from().equals(limit.to())
                    && !reachable(limit.to(), withoutLimit).contains(limit.from())) {
                invalid("config.loopLimits must identify a repeating edge");
            }
        }
        if (containsCycle(adjacencyWithout(byId.keySet(), edges, config.loopLimits()))) {
            invalid("snapshot contains an unbounded cycle");
        }
    }

    private static Map<String, Set<String>> adjacencyWithout(
            Set<String> nodeIds, List<Edge> edges, Set<Route> excluded) {
        Map<String, Set<String>> adjacency = emptyAdjacency(nodeIds);
        for (Edge edge : edges) {
            if (!excluded.contains(edge.route())) adjacency.get(edge.from()).add(edge.to());
        }
        return adjacency;
    }

    private static Map<String, Set<String>> emptyAdjacency(Set<String> nodeIds) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String nodeId : nodeIds) result.put(nodeId, new HashSet<>());
        return result;
    }

    private static Set<String> reachable(String source, Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.push(source);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!visited.add(current)) continue;
            for (String target : adjacency.getOrDefault(current, Set.of())) pending.push(target);
        }
        return visited;
    }

    private static boolean containsCycle(Map<String, Set<String>> adjacency) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String nodeId : adjacency.keySet()) indegree.put(nodeId, 0);
        for (Set<String> targets : adjacency.values()) {
            for (String target : targets) indegree.computeIfPresent(target, (key, count) -> count + 1);
        }
        ArrayDeque<String> pending = new ArrayDeque<>();
        indegree.forEach((nodeId, count) -> { if (count == 0) pending.add(nodeId); });
        int visited = 0;
        while (!pending.isEmpty()) {
            String current = pending.remove();
            visited++;
            for (String target : adjacency.get(current)) {
                int next = indegree.computeIfPresent(target, (key, count) -> count - 1);
                if (next == 0) pending.add(target);
            }
        }
        return visited != adjacency.size();
    }

    private static void rejectExecutionFields(JsonNode value) {
        if (value.isObject()) {
            for (String field : EXECUTION_FIELDS) {
                if (value.has(field)) invalid("snapshot settings must not contain execution context fields");
            }
            value.elements().forEachRemaining(ProfileSnapshotValidator::rejectExecutionFields);
        } else if (value.isArray()) {
            value.elements().forEachRemaining(ProfileSnapshotValidator::rejectExecutionFields);
        }
    }

    private static ObjectNode object(JsonNode value, String field) {
        if (value == null || !value.isObject()) invalid(field + " must be an object");
        return (ObjectNode) value;
    }

    private static Set<String> stringSet(JsonNode value, Pattern pattern, String field) {
        if (value == null || !value.isArray()) invalid(field + " must be an array");
        Set<String> result = new HashSet<>();
        for (JsonNode item : value) {
            if (!result.add(textMatching(item, pattern, field + "[]"))) {
                invalid(field + " contains duplicates");
            }
        }
        return result;
    }

    private static String textMatching(JsonNode value, Pattern pattern, String field) {
        String result = text(value, field);
        if (!pattern.matcher(result).matches()) invalid(field + " is invalid");
        return result;
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            invalid(field + " is invalid");
        }
        return value.textValue();
    }

    private static int positiveInt(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1) {
            invalid(field + " is invalid");
        }
        return value.intValue();
    }

    private static Set<String> fields(ObjectNode value) {
        Set<String> result = new HashSet<>();
        Iterator<String> names = value.fieldNames();
        names.forEachRemaining(result::add);
        return result;
    }

    private static void exactFields(ObjectNode value, Set<String> expected, String field) {
        if (!fields(value).equals(expected)) invalid(field + " contains missing or unknown fields");
    }

    private static void requireProfileKey(String profileKey) {
        if (!PROFILE_KEYS.contains(profileKey)) invalid("profileKey is unsupported");
    }

    private static Map.Entry<String, HandlerContract> handler(
            String key, String nodeType, String... resultPorts) {
        return Map.entry(key, new HandlerContract(nodeType, Set.of(resultPorts)));
    }

    private static void invalid(String message) {
        throw new ProfileVersionException(
                "CONTRACT_VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private record HandlerContract(String nodeType, Set<String> resultPorts) { }
    private record Node(String id, String type, Set<String> resultPorts, JsonNode config) { }
    private record Edge(String from, String resultPort, String to) {
        Route route() { return new Route(from, resultPort, to); }
    }
    private record PortRoute(String from, String resultPort) { }
    private record Route(String from, String resultPort, String to) { }
    private record SnapshotConfig(int maxNodes, int maxAttempts, Set<Route> loopLimits) { }
}
