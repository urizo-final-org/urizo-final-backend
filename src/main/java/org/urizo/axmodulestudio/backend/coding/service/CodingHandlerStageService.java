package org.urizo.axmodulestudio.backend.coding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
import org.urizo.axmodulestudio.backend.coding.dto.CodingToolContract;
import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.StructuredOutputGuard;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingHandlerStageService {

    private static final int MAX_MODEL_TURNS = 8;
    private static final Set<String> CODE_TOOLS = Set.copyOf(
            CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
    private static final Set<String> REVIEW_TOOLS = Set.of(
            "read_file", "search_code", "read_diff", "run_check",
            "check_package_allowlist", "scan_changed_files");

    private final CodingHandlerResultService results;
    private final CodingToolService tools;
    private final CodingModelTurnGuard modelGuard;
    private final CodingModelTurnService models;
    private static final StructuredOutputGuard STRUCTURED_OUTPUT_GUARD =
            new StructuredOutputGuard();

    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingHandlerStageService(
            CodingHandlerResultService results,
            CodingToolService tools,
            CodingModelTurnGuard modelGuard,
            CodingModelTurnService models,
            ObjectMapper objectMapper,
            Clock clock) {
        this.results = Objects.requireNonNull(results, "results are required");
        this.tools = Objects.requireNonNull(tools, "tools are required");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard is required");
        this.models = Objects.requireNonNull(models, "models are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public CodingHandlerContract.StageExecutionResponse execute(
            String authorization,
            UUID jobId,
            int pipelineAttempt,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request) {
        requirePathBinding(resultId, request);
        CodingToolService.StageAuthority authority =
                tools.stageAuthority(authorization, jobId, request.expectedStateVersion());
        if (!authority.traceId().equals(request.traceId())
                || !authority.allowedNodes().contains(authority.graphStep())
                || !authority.allowedCapabilities().contains("CHAT")) {
            throw forbidden("The Coding stage does not match the authoritative Job scope.");
        }
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                results.aggregate(authorization, jobId, pipelineAttempt);
        if (!aggregate.traceId().equals(request.traceId())
                || aggregate.status() != CodingHandlerContract.AttemptStatus.ACTIVE) {
            throw conflict("The Coding pipeline attempt is not active.");
        }
        CodingHandlerContract.StageExecutionResponse replay = replay(
                aggregate, resultId, request.handlerKey());
        if (replay != null) {
            return replay;
        }

        return switch (request.handlerKey()) {
            case "coding.analyze" -> analyze(
                    authorization, jobId, resultId, request, authority, aggregate);
            case "coding.code" -> modelToolStage(
                    authorization, jobId, resultId, request, authority, aggregate,
                    allowedTools(authority.profileAllowedTools(), CODE_TOOLS),
                    Set.of("completed"));
            case "coding.review" -> modelToolStage(
                    authorization, jobId, resultId, request, authority, aggregate,
                    allowedTools(authority.profileAllowedTools(), REVIEW_TOOLS),
                    Set.of("passed", "changes_requested"));
            case "coding.preview" -> preview(
                    authorization, jobId, resultId, request, authority, aggregate);
            case "coding.pr_request" -> sideEffect(
                    resultId, request, aggregate, "requested", "PR_REQUEST_RECORDED");
            case "coding.deploy_request" -> sideEffect(
                    resultId, request, aggregate, "recorded", "DEPLOY_REQUEST_RECORDED");
            default -> throw contract("The Coding stage handler is not registered.");
        };
    }

    private CodingHandlerContract.StageExecutionResponse analyze(
            String authorization,
            UUID jobId,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        CodingModelTurnContract.Response response = modelTurn(
                authorization, jobId, resultId, request, authority, aggregate,
                1, List.of(), initialMessages(request.handlerKey(), aggregate));
        ModelOutcome outcome = parseOutcome(
                request.handlerKey(), response.assistant().content(),
                Set.of("feasible", "infeasible"));
        return response(resultId, request.handlerKey(), outcome.port(), jobId,
                null, null, null, outcome.payload());
    }

    private CodingHandlerContract.StageExecutionResponse modelToolStage(
            String authorization,
            UUID jobId,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            Set<String> allowedTools,
            Set<String> ports) {
        if (!authority.allowedCapabilities().contains("TOOL_CALLING")) {
            throw forbidden("The Coding Job does not allow tool calling.");
        }
        List<JsonNode> schemas = toolSchemas(allowedTools);
        List<JsonNode> messages = new ArrayList<>(
                initialMessages(request.handlerKey(), aggregate));
        JsonNode latestDiff = null;
        CodingModelTurnContract.Response modelResponse = null;
        for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
            modelResponse = modelTurn(
                    authorization, jobId, resultId, request, authority, aggregate,
                    turn, schemas, messages);
            if (modelResponse.toolCalls().isEmpty()) {
                break;
            }
            CodingModelTurnContract.ToolCall call = modelResponse.toolCalls().get(0);
            if (!allowedTools.contains(call.name())) {
                throw contract("The model selected a tool outside the stage allowlist.");
            }
            CodingToolContract.ResultContent toolResult = executeTool(
                    authorization, jobId, request, authority, aggregate,
                    resultId, turn, call);
            messages.add(assistantToolMessage(modelResponse));
            messages.add(toolMessage(toolResult));
            JsonNode decoded = decodeToolResult(toolResult);
            if (Set.of("read_diff", "apply_patch",
                    "check_package_allowlist", "scan_changed_files").contains(call.name())) {
                latestDiff = decoded;
            }
            if (turn == MAX_MODEL_TURNS) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                        "Coding Model exceeded the bounded tool loop.");
            }
        }
        if (modelResponse == null || !modelResponse.toolCalls().isEmpty()) {
            throw contract("The Coding Model did not produce a terminal stage result.");
        }
        ModelOutcome outcome = parseOutcome(
                request.handlerKey(), modelResponse.assistant().content(), ports);
        String candidateSha;
        String diffDigest;
        if ("coding.code".equals(request.handlerKey())) {
            if (latestDiff == null) {
                throw contract("The Coding stage did not establish a diff result.");
            }
            candidateSha = authority.baseSha();
            diffDigest = resultDigest(latestDiff);
        }
        else {
            CodingHandlerContract.HandlerResultResponse code = latestResult(
                    aggregate, "coding.code", "completed");
            candidateSha = code.candidateSha();
            diffDigest = code.diffDigest();
        }
        return response(resultId, request.handlerKey(), outcome.port(),
                aggregate.workspaceId() == null ? jobId : aggregate.workspaceId(),
                candidateSha, diffDigest, null, outcome.payload());
    }

    private CodingHandlerContract.StageExecutionResponse preview(
            String authorization,
            UUID jobId,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        CodingHandlerContract.HandlerResultResponse code = latestResult(
                aggregate, "coding.code", "completed");
        CodingHandlerContract.HandlerResultResponse review = latestResult(
                aggregate, "coding.review", "passed");
        if (!Objects.equals(code.candidateSha(), review.candidateSha())) {
            throw conflict("The reviewed Coding candidate changed before preview.");
        }

        JsonNode diff = executeDeterministicTool(
                authorization, jobId, request, authority, aggregate,
                resultId, 1, "read_diff", objectMapper.createObjectNode());
        JsonNode check = executeDeterministicTool(
                authorization, jobId, request, authority, aggregate,
                resultId, 2, "run_check",
                objectMapper.createObjectNode().put("profile", "git-diff-check"));
        JsonNode packages = executeDeterministicTool(
                authorization, jobId, request, authority, aggregate,
                resultId, 3, "check_package_allowlist", objectMapper.createObjectNode());
        JsonNode scan = executeDeterministicTool(
                authorization, jobId, request, authority, aggregate,
                resultId, 4, "scan_changed_files", objectMapper.createObjectNode());
        if (!"PASSED".equals(check.path("status").asText())
                || !packages.path("passed").asBoolean()
                || !scan.path("passed").asBoolean()) {
            throw new CodingWorkerException(
                    "CODING_CHECK_FAILED",
                    "The deterministic Coding preview checks did not pass.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String diffDigest = resultDigest(diff);
        if (!diffDigest.equals(packages.path("diffDigest").asText())
                || !diffDigest.equals(scan.path("diffDigest").asText())) {
            throw conflict("The Coding diff changed during preview validation.");
        }
        String validationHash = digest(objectMapper.valueToTree(List.of(
                code.candidateSha(), diffDigest,
                check.path("detailsDigest").asText(),
                packages, scan)));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "READY");
        payload.set("changedPaths", diff.path("changedPaths").deepCopy());
        payload.put("checkProfile", check.path("profile").asText());
        return response(resultId, request.handlerKey(), "ready",
                aggregate.workspaceId() == null ? jobId : aggregate.workspaceId(),
                code.candidateSha(), diffDigest, validationHash, payload);
    }

    private CodingHandlerContract.StageExecutionResponse sideEffect(
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            String port,
            String status) {
        CodingHandlerContract.HandlerResultResponse preview = latestResult(
                aggregate, "coding.preview", "ready");
        ObjectNode payload = objectMapper.createObjectNode().put("status", status);
        return response(resultId, request.handlerKey(), port, aggregate.workspaceId(),
                preview.candidateSha(), preview.diffDigest(),
                preview.validationHash(), payload);
    }

    private CodingModelTurnContract.Response modelTurn(
            String authorization,
            UUID jobId,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest stage,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            int turn,
            List<JsonNode> schemas,
            List<JsonNode> messages) {
        UUID turnId = UUID.nameUUIDFromBytes(
                (resultId + ":attempt:" + stage.executionAttempt() + ":model:" + turn)
                        .getBytes(StandardCharsets.UTF_8));
        String key = "stage." + hash(
                resultId + ":attempt:" + stage.executionAttempt() + ":model:" + turn);
        List<String> capabilities = schemas.isEmpty()
                ? List.of("CHAT") : List.of("CHAT", "TOOL_CALLING");
        CodingModelTurnContract.Request request = new CodingModelTurnContract.Request(
                CodingModelTurnContract.SCHEMA_VERSION,
                turnId,
                jobId,
                stage.traceId(),
                key,
                stage.executionAttempt(),
                stage.expectedStateVersion(),
                authority.graphStep(),
                authority.promptVersion(),
                authority.contextDigest(),
                capabilities,
                messages,
                schemas,
                objectMapper.createObjectNode().put("type", "TEXT"),
                authority.expiresAt());
        CodingModelTurnPermit permit = modelGuard.reserve(authorization, request);
        if (permit.replay()) {
            return permit.cachedResponse();
        }
        try {
            CodingModelTurnContract.Response response = models.execute(request);
            modelGuard.complete(permit, response);
            return response;
        }
        catch (ProviderGatewayException failure) {
            try {
                modelGuard.fail(permit, failure.code().name(), retryable(failure.code()));
            }
            catch (RuntimeException ignored) {
                // Preserve the provider failure; the guard lease bounds recovery.
            }
            throw failure;
        }
    }

    private CodingToolContract.ResultContent executeTool(
            String authorization,
            UUID jobId,
            CodingHandlerContract.StageExecutionRequest stage,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            UUID resultId,
            int sequence,
            CodingModelTurnContract.ToolCall call) {
        ObjectNode request = toolRequest(
                jobId, stage, authority, aggregate, resultId, sequence, call);
        CodingToolContract.Accepted accepted = tools.submit(authorization, request);
        return tools.result(authorization, accepted.executionId());
    }

    private JsonNode executeDeterministicTool(
            String authorization,
            UUID jobId,
            CodingHandlerContract.StageExecutionRequest stage,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            UUID resultId,
            int sequence,
            String name,
            JsonNode arguments) {
        UUID callId = UUID.nameUUIDFromBytes(
                (resultId + ":attempt:" + stage.executionAttempt()
                        + ":tool:" + sequence + ":" + name)
                        .getBytes(StandardCharsets.UTF_8));
        CodingToolContract.ResultContent result = executeTool(
                authorization, jobId, stage, authority, aggregate, resultId, sequence,
                new CodingModelTurnContract.ToolCall(callId, name, arguments));
        return decodeToolResult(result);
    }

    private ObjectNode toolRequest(
            UUID jobId,
            CodingHandlerContract.StageExecutionRequest stage,
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            UUID resultId,
            int sequence,
            CodingModelTurnContract.ToolCall call) {
        JsonNode gatewayArguments = gatewayArguments(call);
        String key = "stage-tool." + hash(resultId + ":" + sequence + ":" + call.toolCallId());
        UUID requestId = UUID.nameUUIDFromBytes(
                (resultId + ":request:" + sequence + ":" + call.toolCallId())
                        .getBytes(StandardCharsets.UTF_8));
        String candidate = latestCandidate(aggregate, authority.baseSha());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("schemaVersion", "1.0");
        body.put("messageType", "TOOL_REQUEST");
        body.put("requestId", requestId.toString());
        body.put("toolCallId", call.toolCallId().toString());
        body.put("jobId", jobId.toString());
        body.put("traceId", stage.traceId().toString());
        body.put("leaseId", authority.leaseId().toString());
        body.put("idempotencyKey", key);
        body.put("attempt", stage.executionAttempt());
        body.put("graphStep", authority.graphStep());
        body.put("attemptScope", stage.handlerKey() + ":" + aggregate.pipelineAttempt());
        body.put("expectedStateVersion", stage.expectedStateVersion());
        body.put("deadlineAt", authority.expiresAt().toString());
        body.putObject("actor")
                .put("actorId", authority.actorId().toString())
                .put("projectId", authority.projectId().toString())
                .put("role", "DEVELOPER");
        body.put("jobState", "RUNNING");
        body.putObject("repository")
                .put("repositoryId", authority.repositoryId().toString())
                .put("baseSha", authority.baseSha())
                .put("candidateSha", candidate);
        body.put("contextDigest", authority.contextDigest());
        body.put("policyHash", authority.policyHash());
        body.put("argumentSchemaDigest",
                CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.get(call.name()));
        ArrayNode requestedPaths = body.putArray("requestedPaths");
        if ("read_file".equals(call.name())) {
            requestedPaths.add(gatewayArguments.path("path").asText());
        }
        else if ("search_code".equals(call.name()) && gatewayArguments.has("roots")) {
            gatewayArguments.path("roots").forEach(requestedPaths::add);
        }
        else {
            requestedPaths.add(".");
        }
        ObjectNode tool = body.putObject("tool");
        tool.put("name", call.name());
        tool.set("arguments", gatewayArguments);
        body.putObject("approval")
                .put("approvalId", UUID.nameUUIDFromBytes(
                        (jobId + ":approval").getBytes(StandardCharsets.UTF_8)).toString())
                .put("scopeDigest", authority.policyHash())
                .put("expiresAt", authority.expiresAt().toString());
        return body;
    }

    private JsonNode gatewayArguments(CodingModelTurnContract.ToolCall call) {
        JsonNode source = call.arguments();
        ObjectNode result = objectMapper.createObjectNode();
        switch (call.name()) {
            case "read_file" -> result.put("path", source.path("path").asText());
            case "search_code" -> {
                result.put("query", source.path("query").asText());
                result.putArray("roots").add(source.path("scope").asText("."));
            }
            case "read_diff", "check_package_allowlist", "scan_changed_files" -> { }
            case "apply_patch" -> result.put("patch", source.path("patch").asText());
            case "run_check" -> result.put("checkId", source.path("profile").asText());
            default -> throw contract("The model selected an unregistered Coding tool.");
        }
        return result;
    }

    private List<JsonNode> initialMessages(
            String handlerKey,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("request", aggregate.requestText());
        ArrayNode prior = context.putArray("priorResults");
        aggregate.results().stream()
                .skip(Math.max(0, aggregate.results().size() - 20L))
                .forEach(result -> {
                    ObjectNode item = prior.addObject();
                    item.put("handlerKey", result.handlerKey());
                    item.put("resultPort", result.resultPort());
                    putOptional(item, "candidateSha", result.candidateSha());
                    putOptional(item, "diffDigest", result.diffDigest());
                    putOptional(item, "validationHash", result.validationHash());
                });
        ArrayNode feedback = context.putArray("approvalFeedback");
        aggregate.decisions().stream()
                .filter(decision -> decision.feedback() != null)
                .skip(Math.max(0, aggregate.decisions().stream()
                        .filter(decision -> decision.feedback() != null).count() - 5L))
                .forEach(decision -> feedback.add(decision.feedback()));

        String ports = switch (handlerKey) {
            case "coding.analyze" -> "feasible or infeasible";
            case "coding.code" -> "completed";
            case "coding.review" -> "passed or changes_requested";
            default -> throw contract("The Coding Model stage is not registered.");
        };
        String system = "You are executing " + handlerKey + ". Stay within the supplied request "
                + "and approved tools. When finished, return only JSON with exactly fields port "
                + "and payload. port must be " + ports + " and payload must be an object. "
                + ("coding.code".equals(handlerKey)
                    ? "Use read_diff before any diff-bound tool and again after the final change. "
                    : "Do not request apply_patch in this stage. ");
        return List.of(
                objectMapper.createObjectNode().put("role", "system").put("content", system),
                objectMapper.createObjectNode().put("role", "user")
                        .put("content", encode(context)));
    }

    private List<JsonNode> toolSchemas(Set<String> names) {
        List<JsonNode> schemas = new ArrayList<>();
        for (String name : CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet().stream()
                .sorted().toList()) {
            if (!names.contains(name)) {
                continue;
            }
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("name", name);
            schema.put("description", "Approved AX Module Studio Coding tool " + name + ".");
            schema.put("schemaDigest", CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.get(name));
            schema.set("inputSchema", inputSchema(name));
            schemas.add(schema);
        }
        return List.copyOf(schemas);
    }

    static Set<String> allowedTools(Set<String> profileTools, Set<String> stageTools) {
        Set<String> allowed = new java.util.HashSet<>(stageTools);
        allowed.retainAll(profileTools);
        return Set.copyOf(allowed);
    }

    private ObjectNode inputSchema(String name) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        switch (name) {
            case "read_file" -> stringProperty(properties, required, "path");
            case "search_code" -> {
                stringProperty(properties, required, "query");
                properties.putObject("scope").put("type", "string");
            }
            case "apply_patch" -> stringProperty(properties, required, "patch");
            case "run_check" -> stringProperty(properties, required, "profile");
            case "read_diff", "check_package_allowlist", "scan_changed_files" -> { }
            default -> throw contract("The Coding tool schema is not registered.");
        }
        return schema;
    }

    private static void stringProperty(
            ObjectNode properties, ArrayNode required, String name) {
        properties.putObject(name).put("type", "string");
        required.add(name);
    }

    private ObjectNode assistantToolMessage(CodingModelTurnContract.Response response) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", response.assistant().content());
        ArrayNode calls = message.putArray("toolCalls");
        for (CodingModelTurnContract.ToolCall call : response.toolCalls()) {
            ObjectNode value = calls.addObject();
            value.put("toolCallId", call.toolCallId().toString());
            value.put("name", call.name());
            value.set("arguments", call.arguments());
        }
        return message;
    }

    private ObjectNode toolMessage(CodingToolContract.ResultContent result) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "tool");
        message.put("toolCallId", result.toolCallId().toString());
        message.put("executionId", result.executionId().toString());
        message.putObject("result")
                .put("mediaType", result.mediaType())
                .put("resultRef", "/internal/coding/tool-executions/"
                        + result.executionId() + "/result")
                .put("sizeBytes", result.sizeBytes())
                .put("digest", result.digest());
        message.put("content", result.content());
        return message;
    }

    private JsonNode decodeToolResult(CodingToolContract.ResultContent result) {
        if ("text/plain".equals(result.mediaType())) {
            return objectMapper.createObjectNode().put("content", result.content());
        }
        try {
            JsonNode decoded = objectMapper.readTree(result.content());
            if (!decoded.isObject()) {
                throw contract("The Coding tool result is not an object.");
            }
            return decoded;
        }
        catch (JsonProcessingException failure) {
            throw contract("The Coding tool result is not valid JSON.");
        }
    }

    /**
     * The stage prompt asks for one JSON object, but the model turn contract only
     * carries TEXT, so nothing can force that shape. Models routinely wrap a
     * correct answer in a Markdown fence or a sentence, which is a formatting
     * miss rather than a wrong answer, so one repair pass is allowed before the
     * stage is failed. A second miss is a contract violation and is not retried.
     */
    private ModelOutcome parseOutcome(String handlerKey, String raw, Set<String> ports) {
        StructuredOutputGuard.ValidatedOutput<String> validated;
        try {
            validated = STRUCTURED_OUTPUT_GUARD.validateOrRepair(
                    raw,
                    candidate -> readOutcome(candidate, ports) != null,
                    CodingHandlerStageService::unwrapJsonObject);
        }
        catch (ProviderGatewayException failure) {
            throw contract("The Coding Model stage result is invalid.");
        }
        ModelOutcome outcome = readOutcome(validated.value(), ports);
        if (outcome == null) {
            throw contract("The Coding Model stage result is invalid.");
        }
        return outcome;
    }

    /** Returns null when the text is not the declared stage result object. */
    private ModelOutcome readOutcome(String raw, Set<String> ports) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(raw);
            if (!value.isObject() || value.size() != 2
                    || !value.path("port").isTextual()
                    || !ports.contains(value.path("port").asText())
                    || !value.path("payload").isObject()) {
                return null;
            }
            return new ModelOutcome(
                    value.path("port").asText(), value.path("payload").deepCopy());
        }
        catch (JsonProcessingException failure) {
            return null;
        }
    }

    /**
     * Keeps the outermost JSON object and drops whatever surrounds it, which
     * covers both a Markdown code fence and leading or trailing prose.
     */
    private static String unwrapJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    private static CodingHandlerContract.HandlerResultResponse latestResult(
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            String handlerKey,
            String port) {
        for (int index = aggregate.results().size() - 1; index >= 0; index--) {
            CodingHandlerContract.HandlerResultResponse result = aggregate.results().get(index);
            if (handlerKey.equals(result.handlerKey()) && port.equals(result.resultPort())) {
                return result;
            }
        }
        throw conflict("The Coding stage prerequisite result is missing.");
    }

    private static String latestCandidate(
            CodingHandlerContract.AttemptAggregateResponse aggregate, String fallback) {
        for (int index = aggregate.results().size() - 1; index >= 0; index--) {
            String candidate = aggregate.results().get(index).candidateSha();
            if (candidate != null) {
                return candidate;
            }
        }
        return fallback;
    }

    private static CodingHandlerContract.StageExecutionResponse replay(
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            UUID resultId,
            String handlerKey) {
        List<CodingHandlerContract.HandlerResultResponse> matches = aggregate.results().stream()
                .filter(result -> result.resultId().equals(resultId))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        CodingHandlerContract.ResultType expectedType = switch (handlerKey) {
            case "coding.analyze" -> CodingHandlerContract.ResultType.ANALYSIS;
            case "coding.code" -> CodingHandlerContract.ResultType.CANDIDATE;
            case "coding.review" -> CodingHandlerContract.ResultType.REVIEW;
            case "coding.preview" -> CodingHandlerContract.ResultType.DIFF;
            case "coding.pr_request" -> CodingHandlerContract.ResultType.PULL_REQUEST;
            case "coding.deploy_request" -> CodingHandlerContract.ResultType.DEPLOY_REQUEST;
            default -> throw contract("The Coding stage handler is not registered.");
        };
        if (matches.size() != 1
                || !handlerKey.equals(matches.get(0).handlerKey())
                || expectedType != matches.get(0).resultType()) {
            throw conflict("The Coding stage resultId is already bound to another result.");
        }
        CodingHandlerContract.HandlerResultResponse existing = matches.get(0);
        return response(
                resultId, handlerKey, existing.resultPort(), existing.workspaceId(),
                existing.candidateSha(), existing.diffDigest(), existing.validationHash(),
                existing.payload());
    }

    private static String resultDigest(JsonNode result) {
        String digest = result.hasNonNull("diffDigest")
                ? result.path("diffDigest").asText()
                : result.path("digest").asText();
        if (!digest.matches("^sha256:[0-9a-f]{64}$")) {
            throw contract("The Coding tool result has no valid diff digest.");
        }
        return digest;
    }

    private String digest(JsonNode value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(objectMapper.writeValueAsBytes(value)));
        }
        catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 or JSON encoding is unavailable.", failure);
        }
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private String encode(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw contract("The Coding stage context cannot be encoded.");
        }
    }

    private static CodingHandlerContract.StageExecutionResponse response(
            UUID resultId,
            String handlerKey,
            String port,
            UUID workspaceId,
            String candidateSha,
            String diffDigest,
            String validationHash,
            JsonNode payload) {
        return new CodingHandlerContract.StageExecutionResponse(
                CodingHandlerContract.SCHEMA_VERSION,
                resultId,
                handlerKey,
                port,
                workspaceId,
                candidateSha,
                diffDigest,
                validationHash,
                payload);
    }

    private static void putOptional(ObjectNode target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private static void requirePathBinding(
            UUID resultId, CodingHandlerContract.StageExecutionRequest request) {
        if (!resultId.equals(request.resultId())) {
            throw contract("resultId does not match the Coding stage path.");
        }
    }

    private static boolean retryable(ModelGatewayErrorCode code) {
        return switch (code) {
            case MODEL_RATE_LIMITED, MODEL_TIMEOUT, MODEL_PROVIDER_UNAVAILABLE,
                    INTERNAL_TRANSIENT_ERROR -> true;
            default -> false;
        };
    }

    private static CodingWorkerException contract(String message) {
        return new CodingWorkerException(
                "CONTRACT_VALIDATION_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static CodingWorkerException conflict(String message) {
        return new CodingWorkerException(
                "JOB_STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private static CodingWorkerException forbidden(String message) {
        return new CodingWorkerException(
                "SERVICE_AUTHORIZATION_DENIED", message, HttpStatus.FORBIDDEN);
    }

    private record ModelOutcome(String port, JsonNode payload) { }
}
