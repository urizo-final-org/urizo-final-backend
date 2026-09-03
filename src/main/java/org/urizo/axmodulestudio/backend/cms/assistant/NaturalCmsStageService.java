package org.urizo.axmodulestudio.backend.cms.assistant;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.StructuredOutputGuard;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformClient;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformException;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileModelBindingService;

@Service
@Profile("dev & local-full")
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class NaturalCmsStageService {

    private static final int MAX_MODEL_TURNS = 8;
    private static final Set<String> ANALYZE_PORTS = Set.of("feasible", "infeasible");
    private static final Set<String> RESOURCE_METADATA_FIELDS =
            Set.of("id", "updatedAt", "active");
    private static final StructuredOutputGuard STRUCTURED_OUTPUT_GUARD =
            new StructuredOutputGuard();
    private final NaturalCmsStore store;
    private final NaturalCmsResourceService resources;
    private final CodingModelTurnService models;
    private final ProfileModelBindingService profileModelBindings;
    private final ObjectProvider<McpPlatformClient> mcpClients;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    NaturalCmsStageService(
            NaturalCmsStore store,
            NaturalCmsResourceService resources,
            CodingModelTurnService models,
            ProfileModelBindingService profileModelBindings,
            ObjectProvider<McpPlatformClient> mcpClients,
            ObjectMapper objectMapper,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.resources = Objects.requireNonNull(resources, "resources are required");
        this.models = Objects.requireNonNull(models, "models are required");
        this.profileModelBindings = Objects.requireNonNull(
                profileModelBindings, "profileModelBindings are required");
        this.mcpClients = Objects.requireNonNull(mcpClients, "mcpClients are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public NaturalCmsContract.StageExecutionResponse execute(
            String authorization,
            UUID jobId,
            int pipelineAttempt,
            UUID resultId,
            NaturalCmsContract.StageExecutionRequest request) {
        if (!resultId.equals(request.resultId())) {
            throw contract("resultId does not match the Natural CMS stage path.");
        }
        NaturalCmsContract.JobResponse job = store.get(
                authorization, jobId, pipelineAttempt);
        if (!job.traceId().equals(request.traceId())
                || !job.profileVersionId().equals(request.profileVersionId())
                || job.stateVersion() != request.expectedStateVersion()) {
            throw conflict("Natural CMS stage does not match its Job identity.");
        }
        NaturalCmsContract.HandlerResult replay = store.findResult(
                authorization, jobId, pipelineAttempt, resultId).orElse(null);
        if (replay != null) {
            if (!replay.handlerKey().equals(request.handlerKey())) {
                throw conflict("Natural CMS resultId is already bound.");
            }
            return response(replay);
        }
        Set<String> allowedTools = store.runtimePolicy(
                authorization, job.profileVersionId()).allowedTools();

        if ("cms.apply".equals(request.handlerKey())) {
            JsonNode approvedCommand = revalidateApply(job, allowedTools);
            NaturalCmsContract.HandlerResult stored = store.recordApplied(
                    authorization,
                    jobId,
                    pipelineAttempt,
                    resultId,
                    request.expectedStateVersion(),
                    () -> apply(job, request, resultId, approvedCommand));
            return response(stored);
        }

        NaturalCmsContract.StageExecutionResponse executed = switch (request.handlerKey()) {
            case "cms.analyze" -> analyze(job, request, resultId);
            case "cms.preview" -> preview(job, request, resultId, allowedTools);
            case "cms.discard" -> discard(job, request, resultId, allowedTools);
            default -> throw contract("Natural CMS stage handler is not registered.");
        };
        NaturalCmsContract.HandlerResult stored = store.record(
                authorization, jobId, pipelineAttempt, executed);
        return response(stored);
    }

    private NaturalCmsContract.StageExecutionResponse analyze(
            NaturalCmsContract.JobResponse job,
            NaturalCmsContract.StageExecutionRequest stage,
            UUID resultId) {
        requireStatus(job, "ACTIVE");
        ObjectNode currentState = resources.snapshot(job.resource());
        List<ProviderModelRegistration> modelBindings =
                modelBindings(job, stage, ModelUseCase.CHAT);
        CodingModelTurnContract.Response turn = modelTurn(
                job, stage, resultId, 1, List.of(),
                initialMessages(job, currentState, false), modelBindings);
        ModelOutcome outcome = parseAnalyze(turn.assistant().content());
        return new NaturalCmsContract.StageExecutionResponse(
                NaturalCmsContract.SCHEMA_VERSION,
                resultId,
                stage.handlerKey(),
                outcome.port(),
                job.resource(),
                null,
                null,
                null,
                outcome.value());
    }

    private NaturalCmsContract.StageExecutionResponse preview(
            NaturalCmsContract.JobResponse job,
            NaturalCmsContract.StageExecutionRequest stage,
            UUID resultId,
            Set<String> allowedTools) {
        requireStatus(job, "ACTIVE");
        ObjectNode currentState = resources.snapshot(job.resource());
        Set<String> modelTools = allowedTools(
                allowedTools, NaturalCmsToolContract.PREVIEW_TOOLS);
        List<JsonNode> schemas = toolSchemas(modelTools);
        List<JsonNode> messages = new ArrayList<>(initialMessages(job, currentState, true));
        List<ProviderModelRegistration> modelBindings =
                modelBindings(job, stage, schemas.isEmpty()
                        ? ModelUseCase.CHAT : ModelUseCase.TOOL_CALL);
        CodingModelTurnContract.Response terminal = null;
        for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
            terminal = modelTurn(
                    job, stage, resultId, turn, schemas, messages, modelBindings);
            if (terminal.toolCalls().isEmpty()) {
                break;
            }
            CodingModelTurnContract.ToolCall call = terminal.toolCalls().get(0);
            if (!modelTools.contains(call.name())) {
                throw contract("Model selected a Tool outside the Natural CMS allowlist.");
            }
            JsonNode result = callPreviewTool(
                    job.resource(), currentState, call, allowedTools);
            messages.add(assistantToolMessage(terminal));
            messages.add(toolMessage(resultId, turn, call, result));
            if (turn == MAX_MODEL_TURNS) {
                throw contract("Natural CMS Model exceeded the bounded Tool loop.");
            }
        }
        if (terminal == null || !terminal.toolCalls().isEmpty()) {
            throw contract("Natural CMS Model did not produce a terminal command.");
        }
        JsonNode command = parseCommand(terminal.assistant().content());
        resources.validateCommand(job.resource(), command);

        ObjectNode targetArguments = baseArguments(job.resource(), currentState);
        callTool("resolve_cms_target", targetArguments, allowedTools);
        ObjectNode commandArguments = baseArguments(job.resource(), currentState);
        commandArguments.set("command", command.deepCopy());
        JsonNode validation = callTool(
                "validate_cms_command", commandArguments, allowedTools);
        if (!validation.path("valid").asBoolean()) {
            throw contract("Natural CMS command validation did not pass.");
        }
        JsonNode preview = callTool("create_cms_preview", commandArguments, allowedTools);
        UUID previewId = uuid(preview, "previewId");
        String previewHash = digest(preview, "previewHash");
        return new NaturalCmsContract.StageExecutionResponse(
                NaturalCmsContract.SCHEMA_VERSION,
                resultId,
                stage.handlerKey(),
                "ready",
                job.resource(),
                command,
                previewId,
                previewHash,
                preview);
    }

    private NaturalCmsContract.StageExecutionResponse discard(
            NaturalCmsContract.JobResponse job,
            NaturalCmsContract.StageExecutionRequest stage,
            UUID resultId,
            Set<String> allowedTools) {
        requireDecision(job, "REJECTED");
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("previewId", job.previewId().toString());
        arguments.put("previewHash", job.previewHash());
        JsonNode discarded = callTool("discard_cms_preview", arguments, allowedTools);
        if (!discarded.path("discarded").asBoolean()) {
            throw contract("Natural CMS preview discard did not complete.");
        }
        boolean retry = !store.hasPreviewResult(job.jobId(), job.pipelineAttempt());
        ObjectNode payload = discarded.deepCopy();
        payload.put("retry", retry);
        return new NaturalCmsContract.StageExecutionResponse(
                NaturalCmsContract.SCHEMA_VERSION,
                resultId,
                stage.handlerKey(),
                retry ? "retry" : "discarded",
                job.resource(),
                job.structuredCommand(),
                job.previewId(),
                job.previewHash(),
                payload);
    }

    private JsonNode revalidateApply(
            NaturalCmsContract.JobResponse job,
            Set<String> allowedTools) {
        requireDecision(job, "APPROVED");
        JsonNode command = resources.validateCommand(job.resource(), job.structuredCommand());
        ObjectNode currentState = resources.snapshot(job.resource());
        ObjectNode arguments = baseArguments(job.resource(), currentState);
        arguments.set("command", command.deepCopy());
        arguments.put("previewId", job.previewId().toString());
        arguments.put("previewHash", job.previewHash());
        JsonNode revalidated = callTool(
                "revalidate_cms_preview", arguments, allowedTools);
        if (!revalidated.path("valid").asBoolean()) {
            throw new NaturalCmsException(
                    "CMS_PREVIEW_STALE",
                    "Natural CMS preview changed before approval apply.",
                    HttpStatus.CONFLICT);
        }
        JsonNode ready = callTool("apply_cms_preview", arguments, allowedTools);
        if (!ready.path("applyReady").asBoolean()
                || !ready.path("command").equals(command)) {
            throw contract("Natural CMS apply Tool changed the approved command.");
        }
        return command;
    }

    private NaturalCmsContract.StageExecutionResponse apply(
            NaturalCmsContract.JobResponse job,
            NaturalCmsContract.StageExecutionRequest stage,
            UUID resultId,
            JsonNode command) {
        JsonNode applied = resources.apply(job.resource(), command);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "APPLIED");
        payload.set("resource", applied);
        return new NaturalCmsContract.StageExecutionResponse(
                NaturalCmsContract.SCHEMA_VERSION,
                resultId,
                stage.handlerKey(),
                "applied",
                job.resource(),
                command,
                job.previewId(),
                job.previewHash(),
                payload);
    }

    private CodingModelTurnContract.Response modelTurn(
            NaturalCmsContract.JobResponse job,
            NaturalCmsContract.StageExecutionRequest stage,
            UUID resultId,
            int turn,
            List<JsonNode> schemas,
            List<JsonNode> messages,
            List<ProviderModelRegistration> modelBindings) {
        UUID turnId = UUID.nameUUIDFromBytes(
                (resultId + ":attempt:" + stage.executionAttempt() + ":model:" + turn)
                        .getBytes(StandardCharsets.UTF_8));
        return models.executeNaturalCms(new CodingModelTurnContract.Request(
                CodingModelTurnContract.SCHEMA_VERSION,
                turnId,
                job.jobId(),
                job.traceId(),
                "natural-cms." + hex(resultId + ":" + stage.executionAttempt() + ":" + turn),
                stage.executionAttempt(),
                stage.expectedStateVersion(),
                stage.handlerKey().replace('.', '_'),
                "natural-cms-v1",
                hash(job.jobId() + ":" + job.resource() + ":" + job.requestText()),
                schemas.isEmpty() ? List.of("CHAT") : List.of("CHAT", "TOOL_CALLING"),
                messages,
                schemas,
                objectMapper.createObjectNode().put("type", "TEXT"),
                clock.instant().plusSeconds(60)), modelBindings);
    }

    private List<ProviderModelRegistration> modelBindings(
            NaturalCmsContract.JobResponse job,
            NaturalCmsContract.StageExecutionRequest stage,
            ModelUseCase useCase) {
        return profileModelBindings.resolve(
                job.profileVersionId(), stage.nodeId(), stage.handlerKey(), useCase);
    }

    private List<JsonNode> initialMessages(
            NaturalCmsContract.JobResponse job,
            JsonNode currentState,
            boolean commandStage) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("request", job.requestText());
        context.set("resource", objectMapper.valueToTree(job.resource()));
        context.set("currentState", currentState.deepCopy());
        if (commandStage) {
            ArrayNode editableFields = context.putArray("editableFields");
            currentState.fieldNames().forEachRemaining(name -> {
                if (!RESOURCE_METADATA_FIELDS.contains(name)) {
                    editableFields.add(name);
                }
            });
        }
        if (job.approvalFeedback() != null) {
            context.put("approvalFeedback", job.approvalFeedback());
        }
        String instruction = commandStage
                ? "Create one " + job.resource().type() + " UPDATE command. "
                    + "You may call only declared CMS tools. Finish with only JSON containing "
                    + "operation UPDATE and fields; fields may use only names from editableFields."
                : "Decide feasibility. Return only JSON with exactly fields port and payload; "
                    + "port must be feasible or infeasible and payload must be an object.";
        return List.of(
                objectMapper.createObjectNode().put("role", "system").put("content", instruction),
                objectMapper.createObjectNode().put("role", "user")
                        .put("content", encode(context)));
    }

    private JsonNode callPreviewTool(
            NaturalCmsContract.ResourceRef resource,
            JsonNode currentState,
            CodingModelTurnContract.ToolCall call,
            Set<String> allowedTools) {
        ObjectNode arguments = baseArguments(resource, currentState);
        if (!"resolve_cms_target".equals(call.name())) {
            JsonNode command = call.arguments().path("command");
            resources.validateCommand(resource, command);
            arguments.set("command", command.deepCopy());
        }
        return callTool(call.name(), arguments, allowedTools);
    }

    private JsonNode callTool(
            String name, JsonNode arguments, Set<String> allowedTools) {
        if (!allowedTools.contains(name)) {
            throw new NaturalCmsException(
                    "TOOL_NOT_ALLOWED",
                    "Natural CMS runtime policy rejected the Tool.",
                    HttpStatus.FORBIDDEN);
        }
        McpPlatformClient client = mcpClients.getIfAvailable();
        if (client == null) {
            throw new NaturalCmsException(
                    "MCP_PLATFORM_UNAVAILABLE",
                    "Natural CMS MCP platform is unavailable.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    true);
        }
        try {
            JsonNode result = client.callTool(name, arguments);
            if (result.path("isError").asBoolean(true)
                    || !result.path("structuredContent").isObject()) {
                throw contract("Natural CMS MCP Tool returned an invalid result.");
            }
            return result.path("structuredContent").deepCopy();
        }
        catch (McpPlatformException failure) {
            throw new NaturalCmsException(
                    "MCP_PLATFORM_UNAVAILABLE",
                    "Natural CMS MCP Tool call failed.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    true);
        }
    }

    private ObjectNode baseArguments(
            NaturalCmsContract.ResourceRef resource, JsonNode currentState) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.set("resource", objectMapper.valueToTree(resource));
        arguments.set("currentState", currentState.deepCopy());
        return arguments;
    }

    private List<JsonNode> toolSchemas(Set<String> names) {
        List<JsonNode> schemas = new ArrayList<>();
        for (String name : names.stream().sorted().toList()) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("name", name);
            schema.put("description", "Approved AX Module Studio Natural CMS Tool " + name + ".");
            schema.put("schemaDigest",
                    NaturalCmsToolContract.MODEL_TOOL_SCHEMA_DIGESTS.get(name));
            ObjectNode input = schema.putObject("inputSchema");
            input.put("type", "object");
            input.put("additionalProperties", false);
            ObjectNode properties = input.putObject("properties");
            ArrayNode required = input.putArray("required");
            if (!"resolve_cms_target".equals(name)) {
                properties.putObject("command").put("type", "object");
                required.add("command");
            }
            schemas.add(schema);
        }
        return List.copyOf(schemas);
    }

    static Set<String> allowedTools(Set<String> profileTools, Set<String> stageTools) {
        Set<String> allowed = new java.util.HashSet<>(stageTools);
        allowed.retainAll(profileTools);
        return Set.copyOf(allowed);
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

    private ObjectNode toolMessage(
            UUID resultId,
            int turn,
            CodingModelTurnContract.ToolCall call,
            JsonNode result) {
        String content = encode(result);
        UUID executionId = UUID.nameUUIDFromBytes(
                (resultId + ":tool:" + turn + ":" + call.toolCallId())
                        .getBytes(StandardCharsets.UTF_8));
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "tool");
        message.put("toolCallId", call.toolCallId().toString());
        message.put("executionId", executionId.toString());
        message.putObject("result")
                .put("mediaType", "application/json")
                .put("resultRef", "/internal/natural-cms/tool-results/" + executionId)
                .put("sizeBytes", content.getBytes(StandardCharsets.UTF_8).length)
                .put("digest", hash(content));
        message.put("content", content);
        return message;
    }

    private ModelOutcome parseAnalyze(String value) {
        StructuredOutputGuard.ValidatedOutput<String> validated;
        try {
            validated = STRUCTURED_OUTPUT_GUARD.validateOrRepair(
                    value,
                    candidate -> readAnalyze(candidate) != null,
                    StructuredOutputGuard::extractOutermostJsonObject);
        }
        catch (ProviderGatewayException failure) {
            throw contract("Natural CMS analysis result is invalid.");
        }
        ModelOutcome parsed = readAnalyze(validated.value());
        if (parsed == null) {
            throw contract("Natural CMS analysis result is invalid.");
        }
        return parsed;
    }

    private JsonNode parseCommand(String value) {
        StructuredOutputGuard.ValidatedOutput<String> validated;
        try {
            validated = STRUCTURED_OUTPUT_GUARD.validateOrRepair(
                    value,
                    candidate -> readCommand(candidate) != null,
                    StructuredOutputGuard::extractOutermostJsonObject);
        }
        catch (ProviderGatewayException failure) {
            throw contract("Natural CMS structured command is invalid.");
        }
        JsonNode command = readCommand(validated.value());
        if (command == null) {
            throw contract("Natural CMS structured command is invalid.");
        }
        return command;
    }

    private ModelOutcome readAnalyze(String value) {
        if (value == null) {
            return null;
        }
        JsonNode parsed = StructuredOutputGuard.readSingleJsonObject(
                objectMapper, value);
        if (parsed == null
                || parsed.size() != 2
                || !ANALYZE_PORTS.contains(parsed.path("port").asText())
                || !parsed.path("payload").isObject()) {
            return null;
        }
        return new ModelOutcome(
                parsed.path("port").asText(), parsed.path("payload").deepCopy());
    }

    private JsonNode readCommand(String value) {
        if (value == null) {
            return null;
        }
        return StructuredOutputGuard.readSingleJsonObject(objectMapper, value);
    }

    private static void requireStatus(
            NaturalCmsContract.JobResponse job, String expected) {
        if (!expected.equals(job.status())) {
            throw conflict("Natural CMS Job is not ready for this stage.");
        }
    }

    private static void requireDecision(
            NaturalCmsContract.JobResponse job, String expected) {
        if (!"WAITING_APPROVAL".equals(job.status())
                || !job.previewValid()
                || job.previewId() == null
                || job.previewHash() == null
                || job.structuredCommand() == null
                || !expected.equals(job.approvalDecision())) {
            throw conflict("Natural CMS preview decision is not ready.");
        }
    }

    private NaturalCmsContract.StageExecutionResponse response(
            NaturalCmsContract.HandlerResult result) {
        return new NaturalCmsContract.StageExecutionResponse(
                NaturalCmsContract.SCHEMA_VERSION,
                result.resultId(),
                result.handlerKey(),
                result.resultPort(),
                result.resource(),
                result.structuredCommand(),
                result.previewId(),
                result.previewHash(),
                result.payload());
    }

    private static UUID uuid(JsonNode value, String field) {
        try {
            return UUID.fromString(value.path(field).asText());
        }
        catch (IllegalArgumentException failure) {
            throw contract("Natural CMS preview id is invalid.");
        }
    }

    private static String digest(JsonNode value, String field) {
        String digest = value.path(field).asText();
        if (!digest.matches("^sha256:[0-9a-f]{64}$")) {
            throw contract("Natural CMS preview hash is invalid.");
        }
        return digest;
    }

    private String encode(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw contract("Natural CMS JSON cannot be encoded.");
        }
    }

    private static String hash(String value) {
        return "sha256:" + hex(value);
    }

    private static String hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static NaturalCmsException contract(String message) {
        return new NaturalCmsException(
                "CONTRACT_VALIDATION_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static NaturalCmsException conflict(String message) {
        return new NaturalCmsException(
                "NATURAL_CMS_STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private record ModelOutcome(String port, JsonNode value) { }
}
