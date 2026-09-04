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
    private static final Set<String> LEGACY_AUTHORING_FIELDS = Set.of(
            "nodes", "edges", "config", "modelBindings", "toolPolicy",
            "guardrailProfileKey");
    private static final Set<String> AUTHORING_FIELDS = Set.of(
            "nodes", "edges", "config", "modelBindings", "toolBindings", "toolPolicy",
            "guardrailProfileKey");
    private static final Set<String> LEGACY_SNAPSHOT_FIELDS = Set.of(
            "contractVersion", "profileVersionId", "profileKey", "profileVersion",
            "nodes", "edges", "config", "modelBindings", "toolPolicy",
            "guardrailProfileKey");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "contractVersion", "profileVersionId", "profileKey", "profileVersion",
            "nodes", "edges", "config", "modelBindings", "toolBindings", "toolPolicy",
            "guardrailProfileKey");
    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern HANDLER_KEY = Pattern.compile(
            "^[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)*$");
    private static final Pattern RESULT_PORT = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern MODEL_KEY = Pattern.compile("^[a-z][a-z0-9_-]{0,127}$");
    private static final Pattern TOOL_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,127}$");
    private static final Pattern GUARDRAIL_KEY = Pattern.compile("^[a-z][a-z0-9_.:-]{0,127}$");
    private static final int WORKER_ATTEMPTS = 3;
    private static final String SUPPORTED_GUARDRAIL_PROFILE = "central.default";
    private static final Set<String> EMPTY_CONFIG_HANDLERS = Set.of(
            "common.start", "common.check", "common.end",
            "coding.analyze", "coding.code", "coding.review", "coding.preview",
            "coding.pr_request", "coding.pr_complete", "coding.dev_merge_check", "coding.deploy",
            "cms.analyze", "cms.preview", "cms.discard", "cms.apply");
    private static final Set<String> CODING_APPROVAL_STAGES =
            Set.of("SCOPE", "CANDIDATE", "GITHUB", "CMS", "DEPLOY");
    private static final Set<String> CODING_APPROVAL_ROLES =
            Set.of("GENERAL_ADMIN", "SUPER_ADMIN");

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
            handler("coding.pr_complete", "tool", "completed"),
            handler("coding.dev_merge_check", "check", "merged", "not_merged", "blocked"),
            handler("coding.deploy_request", "tool", "recorded"),
            handler("coding.deploy", "tool", "completed", "blocked"),
            handler("coding.rework_gate", "check", "retry", "handover"),
            handler("cms.analyze", "agent", "feasible", "infeasible"),
            handler("cms.preview", "agent", "ready"),
            handler("cms.discard", "tool", "retry", "discarded"),
            handler("cms.apply", "tool", "applied"),
            handler("cms.approval", "approval", "approved", "rejected"));

    private ProfileSnapshotValidator() { }

    static void validateAuthoring(String profileKey, JsonNode authoringSnapshot) {
        validateAuthoring(profileKey, authoringSnapshot, true);
    }

    static void validateLegacyDefault(String profileKey, JsonNode authoringSnapshot) {
        validateAuthoring(profileKey, authoringSnapshot, false);
    }

    private static void validateAuthoring(
            String profileKey, JsonNode authoringSnapshot, boolean requireToolBindings) {
        requireProfileKey(profileKey);
        ObjectNode authoring = object(authoringSnapshot, "snapshot");
        exactFields(authoring,
                requireToolBindings || authoring.has("toolBindings")
                        ? AUTHORING_FIELDS : LEGACY_AUTHORING_FIELDS,
                "snapshot");

        ObjectNode full = JsonNodeFactory.instance.objectNode();
        full.put("contractVersion", "1.0");
        full.put("profileVersionId", "11111111-1111-4111-8111-111111111111");
        full.put("profileKey", profileKey);
        full.put("profileVersion", 1);
        for (String field : fields(authoring)) {
            full.set(field, authoring.get(field).deepCopy());
        }
        validate(full, UUID.fromString(full.path("profileVersionId").asText()), profileKey, 1,
                requireToolBindings);
    }

    static void validateStored(
            UUID profileVersionId, String profileKey, int profileVersion, JsonNode snapshot) {
        validate(snapshot, profileVersionId, profileKey, profileVersion, false);
    }

    static void validateForActivation(
            UUID profileVersionId, String profileKey, int profileVersion, JsonNode snapshot) {
        validate(snapshot, profileVersionId, profileKey, profileVersion, true);
    }

    private static void validate(
            JsonNode raw,
            UUID expectedProfileVersionId,
            String expectedProfileKey,
            int expectedProfileVersion,
            boolean requireToolBindings) {
        requireProfileKey(expectedProfileKey);
        ObjectNode snapshot = object(raw, "snapshot");
        exactFields(snapshot,
                requireToolBindings || snapshot.has("toolBindings")
                        ? SNAPSHOT_FIELDS : LEGACY_SNAPSHOT_FIELDS,
                "snapshot");
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
        validateModelBindings(modelBindings, expectedProfileKey);
        ObjectNode toolPolicy = object(snapshot.get("toolPolicy"), "snapshot.toolPolicy");
        rejectExecutionFields(toolPolicy);
        validateToolPolicy(toolPolicy, expectedProfileKey);
        if (!SUPPORTED_GUARDRAIL_PROFILE.equals(textMatching(
                snapshot.get("guardrailProfileKey"), GUARDRAIL_KEY,
                "snapshot.guardrailProfileKey"))) {
            invalid("snapshot.guardrailProfileKey is not supported by this runtime");
        }
        ProfileToolBindingPolicy toolBindings = decodeToolBindings(snapshot, expectedProfileKey);
        if (requireToolBindings && toolBindings.legacy()) {
            invalid("snapshot.toolBindings is required for new or activated Profile Versions");
        }
        validateGraph(nodes, edges, config, modelBindings);
        if (!toolBindings.legacy()) {
            validateToolBindings(nodes, toolBindings, expectedProfileKey);
            validateSemanticTopology(nodes, edges, expectedProfileKey);
        }
    }

    private static ProfileToolBindingPolicy decodeToolBindings(
            JsonNode snapshot, String profileKey) {
        try {
            return ProfileToolBindingPolicy.decode(snapshot, TOOLS.get(profileKey));
        }
        catch (IllegalArgumentException failure) {
            invalid("snapshot.toolBindings is invalid");
            throw failure;
        }
    }

    private static void validateModelBindings(ObjectNode bindings, String profileKey) {
        bindings.fields().forEachRemaining(entry -> {
            ObjectNode binding = object(entry.getValue(),
                    "snapshot.modelBindings." + entry.getKey());
            Set<String> bindingFields = fields(binding);
            if (!Set.of("primary", "fallback", "selections").containsAll(bindingFields)
                    || !bindingFields.containsAll(Set.of("primary", "fallback"))) {
                invalid("snapshot.modelBindings binding fields are invalid");
            }
            String primary = textMatching(binding.get("primary"), MODEL_KEY,
                    "snapshot.modelBindings." + entry.getKey() + ".primary");
            Set<String> fallback = stringSet(binding.get("fallback"), MODEL_KEY,
                    "snapshot.modelBindings." + entry.getKey() + ".fallback");
            if (fallback.contains(primary)) {
                invalid("snapshot.modelBindings fallback must not repeat the primary model");
            }
            JsonNode selections = binding.path("selections");
            if ((!selections.isMissingNode() && !selections.isObject())
                    || (!selectionKnown(selections, primary)
                    && !ProfileModelBindingService.isRegisteredBindingKey(profileKey, primary))
                    || fallback.stream().anyMatch(bindingKey -> !selectionKnown(selections, bindingKey)
                            && !ProfileModelBindingService.isRegisteredBindingKey(profileKey, bindingKey))) {
                invalid("snapshot.modelBindings contains an unregistered binding for this Profile");
            }
        });
    }

    private static boolean selectionKnown(JsonNode selections, String selectionId) {
        JsonNode selection = selections.path(selectionId);
        return selection.isObject() && selection.size() == 3
                && selection.path("provider").isTextual() && selection.path("model").isTextual()
                && selection.path("inference").isObject()
                && selection.path("inference").size() >= 1
                && selection.path("inference").size() <= 2
                && selection.path("inference").path("reasoningIntensity").isTextual();
    }

    private static void validateToolPolicy(ObjectNode toolPolicy, String profileKey) {
        exactFields(toolPolicy, Set.of("allowedTools"), "snapshot.toolPolicy");
        Set<String> allowed = stringSet(
                toolPolicy.get("allowedTools"), TOOL_KEY, "snapshot.toolPolicy.allowedTools");
        if (!TOOLS.get(profileKey).containsAll(allowed)) {
            invalid("snapshot.toolPolicy contains an unregistered Tool");
        }
    }

    private static void validateToolBindings(
            List<Node> nodes,
            ProfileToolBindingPolicy policy,
            String profileKey) {
        Map<String, Map<String, ProfileToolBindingPolicy.Mode>> expected = new HashMap<>();
        if ("LLM_OPS".equals(profileKey)) {
            Node code = requiredNode(nodes, "coding.code", null);
            Node review = requiredNode(nodes, "coding.review", null);
            Node preview = requiredNode(nodes, "coding.preview", null);
            expected.put(code.id(), modes(
                    ProfileToolBindingPolicy.Mode.MODEL_OPTIONAL,
                    "read_file", "search_code", "read_diff", "apply_patch", "run_check",
                    "check_package_allowlist", "scan_changed_files"));
            expected.put(review.id(), modes(
                    ProfileToolBindingPolicy.Mode.MODEL_OPTIONAL,
                    "read_file", "search_code", "read_diff", "run_check",
                    "check_package_allowlist", "scan_changed_files"));
            expected.put(preview.id(), modes(
                    ProfileToolBindingPolicy.Mode.SYSTEM_REQUIRED,
                    "read_diff", "run_check", "check_package_allowlist",
                    "scan_changed_files"));
        }
        else {
            Node preview = requiredNode(nodes, "cms.preview", null);
            Node discard = requiredNode(nodes, "cms.discard", null);
            Node apply = requiredNode(nodes, "cms.apply", null);
            expected.put(preview.id(), Map.of(
                    "validate_cms_command", ProfileToolBindingPolicy.Mode.MODEL_REQUIRED,
                    "resolve_cms_target", ProfileToolBindingPolicy.Mode.SYSTEM_REQUIRED,
                    "create_cms_preview", ProfileToolBindingPolicy.Mode.SYSTEM_REQUIRED));
            expected.put(discard.id(), Map.of(
                    "discard_cms_preview", ProfileToolBindingPolicy.Mode.SYSTEM_REQUIRED));
            expected.put(apply.id(), Map.of(
                    "revalidate_cms_preview", ProfileToolBindingPolicy.Mode.SYSTEM_REQUIRED,
                    "apply_cms_preview", ProfileToolBindingPolicy.Mode.SYSTEM_REQUIRED));
        }
        if ("LLM_OPS".equals(profileKey)) {
            if (!expected.keySet().containsAll(policy.bindings().keySet())) {
                invalid("snapshot.toolBindings contains an unsupported node binding");
            }
            policy.bindings().forEach((nodeId, bindings) -> {
                if (!expected.get(nodeId).entrySet().containsAll(bindings.entrySet())) {
                    invalid("snapshot.toolBindings contains an unsupported Coding Tool binding");
                }
            });
            Node preview = requiredNode(nodes, "coding.preview", null);
            if (!expected.get(preview.id()).equals(
                    policy.bindings().getOrDefault(preview.id(), Map.of()))) {
                invalid("snapshot.toolBindings is missing a required Coding system Tool");
            }
        }
        else if (!policy.bindings().equals(expected)) {
            invalid("snapshot.toolBindings does not match the required Profile Tool bindings");
        }
    }

    private static Map<String, ProfileToolBindingPolicy.Mode> modes(
            ProfileToolBindingPolicy.Mode mode, String... tools) {
        Map<String, ProfileToolBindingPolicy.Mode> result = new HashMap<>();
        for (String tool : tools) result.put(tool, mode);
        return Map.copyOf(result);
    }

    private static void validateSemanticTopology(
            List<Node> nodes, List<Edge> edges, String profileKey) {
        Map<String, Node> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        Node start = nodes.stream().filter(node -> "start".equals(node.type()))
                .findFirst().orElseThrow();
        Map<String, Set<String>> adjacency = emptyAdjacency(byId.keySet());
        for (Edge edge : edges) adjacency.get(edge.from()).add(edge.to());

        if ("LLM_OPS".equals(profileKey)) {
            Node analyze = requiredNode(nodes, "coding.analyze", null);
            Node scopeApproval = requiredNode(nodes, "coding.approval", "SCOPE");
            Node code = requiredNode(nodes, "coding.code", null);
            Node review = requiredNode(nodes, "coding.review", null);
            Node preview = requiredNode(nodes, "coding.preview", null);
            Node changeApproval = requiredNode(nodes, "coding.preview_approval", "CANDIDATE");
            Node prRequest = requiredNode(nodes, "coding.pr_request", null);
            Node githubApproval = requiredNode(nodes, "coding.approval", "GITHUB");
            Node prComplete = requiredNode(nodes, "coding.pr_complete", null);
            Node deployRequest = requiredNode(nodes, "coding.deploy_request", null);
            Node deployApproval = requiredNode(nodes, "coding.approval", "DEPLOY");
            Node mergeCheck = requiredNode(nodes, "coding.dev_merge_check", null);
            Node deploy = requiredNode(nodes, "coding.deploy", null);
            if (nodes.stream().filter(node -> "coding.approval".equals(node.handlerKey())).count()
                    != 3) {
                invalid("LLM_OPS contains a duplicate or unsupported approval stage");
            }
            requirePrecedence(start, adjacency, List.of(
                    analyze, scopeApproval, code, review, preview, changeApproval,
                    prRequest, githubApproval, prComplete, deployRequest,
                    deployApproval, mergeCheck, deploy));
            requirePortLeadsTo(scopeApproval, "approved", code, byId, edges);
            requirePortLeadsTo(changeApproval, "approved", prRequest, byId, edges);
            requirePortCannotBypass(changeApproval, "rejected", prRequest, byId, edges);
            requirePortLeadsTo(githubApproval, "approved", prComplete, byId, edges);
            requirePortLeadsTo(deployApproval, "approved", mergeCheck, byId, edges);
        }
        else {
            Node analyze = requiredNode(nodes, "cms.analyze", null);
            Node preview = requiredNode(nodes, "cms.preview", null);
            Node approval = requiredNode(nodes, "cms.approval", "PREVIEW");
            Node apply = requiredNode(nodes, "cms.apply", null);
            Node discard = requiredNode(nodes, "cms.discard", null);
            requirePrecedence(start, adjacency, List.of(analyze, preview, approval));
            requireDominates(start, approval, apply, adjacency);
            requireDominates(start, approval, discard, adjacency);
            requirePortLeadsTo(approval, "approved", apply, byId, edges);
            requirePortCannotBypass(approval, "approved", discard, byId, edges);
            requirePortLeadsTo(approval, "rejected", discard, byId, edges);
            requirePortCannotBypass(approval, "rejected", apply, byId, edges);
        }
    }

    private static Node requiredNode(
            List<Node> nodes, String handlerKey, String approvalStage) {
        List<Node> matches = nodes.stream()
                .filter(node -> handlerKey.equals(node.handlerKey()))
                .filter(node -> approvalStage == null
                        || approvalStage.equals(node.config().path("stage").asText()))
                .toList();
        if (matches.size() != 1) {
            invalid("snapshot requires exactly one " + handlerKey
                    + (approvalStage == null ? "" : " stage " + approvalStage));
        }
        return matches.get(0);
    }

    private static void requirePrecedence(
            Node start, Map<String, Set<String>> adjacency, List<Node> stages) {
        for (int index = 1; index < stages.size(); index++) {
            requireDominates(start, stages.get(index - 1), stages.get(index), adjacency);
        }
    }

    private static void requireDominates(
            Node start,
            Node required,
            Node protectedNode,
            Map<String, Set<String>> adjacency) {
        Map<String, Set<String>> withoutRequired = withoutNode(adjacency, required.id());
        if (reachable(start.id(), withoutRequired).contains(protectedNode.id())) {
            invalid(required.handlerKey() + " must precede " + protectedNode.handlerKey()
                    + " on every path");
        }
    }

    private static void requirePortLeadsTo(
            Node source,
            String resultPort,
            Node requiredTarget,
            Map<String, Node> byId,
            List<Edge> edges) {
        String target = routeTarget(source, resultPort, edges);
        Map<String, Set<String>> adjacency = adjacencyWithoutNode(byId.keySet(), edges, source.id());
        if (!reachable(target, adjacency).contains(requiredTarget.id())) {
            invalid(source.handlerKey() + "." + resultPort
                    + " must lead to " + requiredTarget.handlerKey());
        }
    }

    private static void requirePortCannotBypass(
            Node source,
            String resultPort,
            Node protectedNode,
            Map<String, Node> byId,
            List<Edge> edges) {
        String target = routeTarget(source, resultPort, edges);
        Map<String, Set<String>> adjacency = adjacencyWithoutNode(byId.keySet(), edges, source.id());
        if (reachable(target, adjacency).contains(protectedNode.id())) {
            invalid(source.handlerKey() + "." + resultPort
                    + " bypasses its approval decision");
        }
    }

    private static String routeTarget(Node source, String resultPort, List<Edge> edges) {
        return edges.stream()
                .filter(edge -> source.id().equals(edge.from())
                        && resultPort.equals(edge.resultPort()))
                .map(Edge::to)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("validated result route is missing"));
    }

    private static Map<String, Set<String>> adjacencyWithoutNode(
            Set<String> nodeIds, List<Edge> edges, String excludedNode) {
        Map<String, Set<String>> adjacency = emptyAdjacency(nodeIds);
        for (Edge edge : edges) {
            if (!excludedNode.equals(edge.from()) && !excludedNode.equals(edge.to())) {
                adjacency.get(edge.from()).add(edge.to());
            }
        }
        return adjacency;
    }

    private static Map<String, Set<String>> withoutNode(
            Map<String, Set<String>> adjacency, String excludedNode) {
        Map<String, Set<String>> result = emptyAdjacency(adjacency.keySet());
        adjacency.forEach((source, targets) -> {
            if (!excludedNode.equals(source)) {
                for (String target : targets) {
                    if (!excludedNode.equals(target)) result.get(source).add(target);
                }
            }
        });
        return result;
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
            validateHandlerConfig(handlerKey, nodeConfig);
            result.add(new Node(id, type, handlerKey, ports, nodeConfig.deepCopy()));
        }
        return result;
    }

    private static void validateHandlerConfig(String handlerKey, ObjectNode config) {
        if ("common.approval".equals(handlerKey)) {
            invalid("common.approval is unavailable without a Backend approval authority");
        }
        if (EMPTY_CONFIG_HANDLERS.contains(handlerKey) && !config.isEmpty()) {
            invalid(handlerKey + " config must be empty");
        }
        if ("common.guardrail".equals(handlerKey)
                && (config.size() != 1 || !config.path("locked").isBoolean()
                || !config.path("locked").booleanValue())) {
            invalid("common.guardrail config must be exactly {locked: true}");
        }
        if ("coding.deploy_request".equals(handlerKey)
                && (config.size() != 1
                || !"request_record_only".equals(config.path("mode").textValue()))) {
            invalid("coding.deploy_request config is invalid");
        }
        if ("coding.rework_gate".equals(handlerKey)
                && (config.size() != 1
                || !config.path("maxReworkRounds").isIntegralNumber()
                || config.path("maxReworkRounds").bigIntegerValue().signum() < 1)) {
            invalid("coding.rework_gate config is invalid");
        }
        if ("coding.approval".equals(handlerKey)) {
            validateCodingApprovalConfig(config, false);
        }
        if ("coding.preview_approval".equals(handlerKey)) {
            validateCodingApprovalConfig(config, true);
        }
        if ("cms.approval".equals(handlerKey)
                && (config.size() != 2
                || !"PREVIEW".equals(config.path("stage").textValue())
                || !"GENERAL_ADMIN".equals(config.path("requiredRole").textValue()))) {
            invalid("cms.approval config is invalid");
        }
    }

    private static void validateCodingApprovalConfig(ObjectNode config, boolean candidate) {
        if (!fields(config).equals(Set.of("stage", "requiredRole"))) {
            invalid("coding approval config is invalid");
        }
        String stage = text(config.get("stage"), "node.config.stage");
        String requiredRole = text(config.get("requiredRole"), "node.config.requiredRole");
        if (!CODING_APPROVAL_STAGES.contains(stage)
                || !CODING_APPROVAL_ROLES.contains(requiredRole)
                || candidate != "CANDIDATE".equals(stage)) {
            invalid("coding approval config is invalid");
        }
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
        if (maxAttempts != WORKER_ATTEMPTS) {
            invalid("config.maxAttempts must be exactly 3");
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
            if ("failed".equals(edge.resultPort())
                    && Set.of("common.guardrail", "common.check")
                    .contains(source.handlerKey())
                    && !edge.to().equals(end.id())) {
                invalid(source.handlerKey() + " failed result must route directly to end");
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
    private record Node(
            String id,
            String type,
            String handlerKey,
            Set<String> resultPorts,
            JsonNode config) { }
    private record Edge(String from, String resultPort, String to) {
        Route route() { return new Route(from, resultPort, to); }
    }
    private record PortRoute(String from, String resultPort) { }
    private record Route(String from, String resultPort, String to) { }
    private record SnapshotConfig(int maxNodes, int maxAttempts, Set<Route> loopLimits) { }
}
