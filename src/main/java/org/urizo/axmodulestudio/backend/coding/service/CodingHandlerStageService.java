package org.urizo.axmodulestudio.backend.coding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailRuleContract;
import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.coding.integration.DeploymentAdapter;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.StructuredOutputGuard;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileModelBindingService;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingHandlerStageService {

    /**
     * A complete code exchange already spends about seven turns - read_diff, read_file,
     * apply_patch, the post-change read_diff, the scans and the terminal reply - so a
     * bound of eight left no room for a single re-asked miss. Twelve keeps the loop
     * bounded while allowing the re-ask layer the handful of turns it exists to spend.
     */
    private static final int MAX_MODEL_TURNS = 12;
    /** Tool refusals the model itself caused and can correct when told why. */
    private static final Set<String> REASKABLE_TOOL_FAILURES = Set.of(
            "TOOL_RESULT_NOT_READY", "TOOL_EXECUTION_FAILED", "PATH_POLICY_DENIED",
            "TOOL_ARGUMENTS_INVALID");
    /**
     * The workspace applies a patch with git apply --check --whitespace=error-all, so a
     * hunk is refused unless its context matches the file exactly. A model that guessed
     * the context needs to be pointed at the real bytes, not just told to try again.
     */
    private static final String APPLY_PATCH_RETRY_HINT =
            " If the patch did not apply, call read_file on the target file first and "
            + "rebuild the patch against its exact current content: correct @@ line "
            + "numbers, context lines copied verbatim, and no added trailing whitespace.";
    private static final Set<String> CODE_TOOLS = Set.copyOf(
            CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
    /**
     * The files that declare a dependency. Lock files are included: a library arrives through
     * one just as surely as through the manifest that names it.
     */
    private static final Set<String> DEPENDENCY_MANIFESTS = Set.of(
            "pom.xml", "package.json", "package-lock.json",
            "pnpm-lock.yaml", "yarn.lock");

    private static final Set<String> REVIEW_TOOLS = Set.of(
            "read_file", "search_code", "read_diff", "run_check",
            "check_package_allowlist", "scan_changed_files");

    private final CodingHandlerResultService results;
    private final CodingToolService tools;
    private final CodingModelTurnGuard modelGuard;
    private final CodingModelTurnService models;
    private final ProfileModelBindingService profileModelBindings;
    private final GuardrailPathSelectionService guardrailSelections;
    private final GuardrailRuleService guardrailRules;
    private static final StructuredOutputGuard STRUCTURED_OUTPUT_GUARD =
            new StructuredOutputGuard();

    private final CodingRunnerService runner;
    private final DeploymentAdapter deploymentAdapter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingHandlerStageService(
            CodingHandlerResultService results,
            CodingToolService tools,
            CodingModelTurnGuard modelGuard,
            CodingModelTurnService models,
            CodingRunnerService runner,
            DeploymentAdapter deploymentAdapter,
            ProfileModelBindingService profileModelBindings,
            GuardrailPathSelectionService guardrailSelections,
            GuardrailRuleService guardrailRules,
            ObjectMapper objectMapper,
            Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner is required");
        this.deploymentAdapter = Objects.requireNonNull(
                deploymentAdapter, "deploymentAdapter is required");
        this.results = Objects.requireNonNull(results, "results are required");
        this.tools = Objects.requireNonNull(tools, "tools are required");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard is required");
        this.models = Objects.requireNonNull(models, "models are required");
        this.profileModelBindings = Objects.requireNonNull(
                profileModelBindings, "profileModelBindings are required");
        this.guardrailSelections = Objects.requireNonNull(
                guardrailSelections, "guardrailSelections are required");
        this.guardrailRules = Objects.requireNonNull(
                guardrailRules, "guardrailRules are required");
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
                    modelTools(authority, request.nodeId(), CODE_TOOLS),
                    Set.of("completed"));
            case "coding.review" -> modelToolStage(
                    authorization, jobId, resultId, request, authority, aggregate,
                    modelTools(authority, request.nodeId(), REVIEW_TOOLS),
                    Set.of("passed", "changes_requested"));
            case "coding.preview" -> preview(
                    authorization, jobId, resultId, request, authority, aggregate);
            case "coding.pr_request" -> sideEffect(
                    resultId, request, aggregate, "requested", "PR_REQUEST_RECORDED");
            case "coding.pr_complete" -> completePullRequest(
                    jobId, resultId, request, aggregate);
            case "coding.dev_merge_check" -> checkDevMerge(resultId, request, aggregate);
            case "coding.deploy_request" -> deployRequest(
                    jobId, resultId, request, aggregate);
            case "coding.deploy" -> deploy(resultId, request, aggregate);
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
                1, List.of(), initialMessages(request.handlerKey(), aggregate),
                outcomeResponseFormat(),
                modelBindings(authority, request, ModelUseCase.STRUCTURED_OUTPUT));
        if (!(response.responseFormat()
                instanceof CodingModelTurnContract.JsonSchemaResponseFormat structured)) {
            throw contract("The Coding analyze result is not structured output.");
        }
        ModelOutcome outcome = parseOutcome(
                structured.structuredOutput(),
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
        List<ProviderModelRegistration> modelBindings =
                modelBindings(authority, request, schemas.isEmpty()
                        ? ModelUseCase.CHAT : ModelUseCase.TOOL_CALL);
        for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
            modelResponse = modelTurn(
                    authorization, jobId, resultId, request, authority, aggregate,
                    turn, schemas, messages,
                    objectMapper.createObjectNode().put("type", "TEXT"),
                    modelBindings);
            if (modelResponse.toolCalls().isEmpty()) {
                break;
            }
            CodingModelTurnContract.ToolCall call = modelResponse.toolCalls().get(0);
            if (!allowedTools.contains(call.name())) {
                throw contract("The model selected a tool outside the stage allowlist.");
            }
            CodingToolContract.ResultContent toolResult;
            try {
                toolResult = executeTool(
                        authorization, jobId, request, authority, aggregate,
                        resultId, turn, call);
            }
            catch (CodingToolException failure) {
                // A refusal the model caused is handed back as feedback instead of ending
                // the Job, because the refusal reason is already a correction instruction.
                // Anything else - authority, storage, gateway - stays fatal.
                if (!REASKABLE_TOOL_FAILURES.contains(failure.code())
                        || turn == MAX_MODEL_TURNS) {
                    throw failure;
                }
                messages.add(plainAssistantMessage(modelResponse));
                messages.add(userMessage("Your " + call.name() + " call was refused: "
                        + failure.getMessage()
                        + " Correct the call and continue the task."
                        + ("apply_patch".equals(call.name()) ? APPLY_PATCH_RETRY_HINT : "")));
                continue;
            }
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
        requireSystemTools(authority, request.nodeId(), Set.of(
                "read_diff", "run_check", "check_package_allowlist", "scan_changed_files"));
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
        List<String> denied = deniedChangedPaths(diff, scan);
        if (!denied.isEmpty()) {
            // No approval path exists past this point. A fixed Denylist that a person can wave
            // through is not a Denylist.
            throw new CodingWorkerException(
                    "CODING_GUARDRAIL_PATH_DENIED",
                    "The Coding candidate changed files the fixed guardrail forbids: "
                            + String.join(", ", denied),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // The second layer: the folders the administrator chose when this job was created. The
        // copy taken then is used, not what the setting says now.
        List<String> outside = outsideAllowedFolders(
                guardrailSelections.jobSnapshot(jobId), diff, scan);
        if (!outside.isEmpty()) {
            throw new CodingWorkerException(
                    "CODING_GUARDRAIL_PATH_NOT_SELECTED",
                    "The Coding candidate changed files outside the selected folders: "
                            + String.join(", ", outside),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // The third layer: the rules that name no path at all. They cannot be judged before the
        // model runs, because a request that sounds small can still produce a thousand lines.
        // Build and test success are not repeated here; the deterministic checks above already
        // require them.
        List<String> broken = brokenRules(
                guardrailRules.jobRules(jobId).orElse(null), diff, scan);
        if (!broken.isEmpty()) {
            throw new CodingWorkerException(
                    "CODING_GUARDRAIL_RULE_DENIED",
                    "The Coding candidate breaks the guardrail rules: "
                            + String.join("; ", broken),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String validationHash = digest(objectMapper.valueToTree(List.of(
                code.candidateSha(), diffDigest,
                check.path("detailsDigest").asText(),
                packages, scan)));
        // The deterministic checks passed, so the candidate is worth showing. The
        // runner owns Docker, so the stack is raised through its queue rather than
        // from here. BUILD is queued first because PREVIEW_UP starts with
        // --no-build and the runner claims one PENDING row at a time in order.
        String workspaceId = aggregate.workspaceId() == null
                ? null : aggregate.workspaceId().toString();
        ObjectNode runnerPayload = objectMapper.createObjectNode().put("repo", "backend");
        if (workspaceId != null) {
            runnerPayload.put("workspaceId", workspaceId);
        }
        runner.enqueue("BUILD", runnerPayload);
        ObjectNode previewPayload = objectMapper.createObjectNode();
        if (workspaceId != null) {
            previewPayload.put("workspaceId", workspaceId);
        }
        runner.enqueue("PREVIEW_UP", previewPayload);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "READY");
        payload.set("changedPaths", diff.path("changedPaths").deepCopy());
        payload.put("checkProfile", check.path("profile").asText());
        return response(resultId, request.handlerKey(), "ready",
                aggregate.workspaceId() == null ? jobId : aggregate.workspaceId(),
                code.candidateSha(), diffDigest, validationHash, payload);
    }

    /**
     * The guardrail verdict, taken from what Git reports rather than from what the model says it
     * changed. A model that does not know how far it reached and a model that is describing its
     * work falsely both fail here, so its intent never has to be judged.
     *
     * <p>{@code read_diff} and {@code scan_changed_files} each list the changed files. Both are
     * read so that a disagreement between the two cannot become a gap, even though the digest
     * check above already requires them to describe the same diff.
     */
    static List<String> deniedChangedPaths(JsonNode... toolResults) {
        return GuardrailPathPolicy.deniedPaths(changedPaths(toolResults));
    }

    /**
     * The changed files that fall outside the folders this job was allowed into.
     *
     * <p>An empty allow list means the administrator has not chosen any folder yet, and then only
     * the fixed Denylist applies. Refusing everything instead would stop ordinary work the moment
     * nobody had filled the screen in, while the paths that actually matter stay closed either way.
     *
     * <p>The allow list entries are {@code repository:path}; only the path is compared. The Coding
     * pipeline works on the Backend checkout today and a job carries no repository name, while the
     * two repositories' folder shapes do not overlap - Backend selections sit under
     * {@code src/main/java/...} and Frontend ones under {@code src/features/...}, {@code src/app},
     * {@code src/shared/...} and {@code src/styles}. When the pipeline gains a second repository,
     * the repository has to come from the job rather than from the shape of the path.
     */
    static List<String> outsideAllowedFolders(
            List<String> allowedPaths, JsonNode... toolResults) {
        if (allowedPaths.isEmpty()) {
            return List.of();
        }
        List<String> folders = allowedPaths.stream()
                .map(entry -> entry.substring(entry.indexOf(':') + 1))
                .filter(folder -> !folder.isBlank())
                .toList();
        return changedPaths(toolResults).stream()
                .filter(path -> folders.stream().noneMatch(
                        folder -> path.equals(folder) || path.startsWith(folder + "/")))
                .toList();
    }

    /**
     * The guardrail rules that the finished candidate breaks, described for the person reading the
     * failure rather than as codes.
     *
     * <p>{@code rules} is null when the job carries no copy, which happens only for a job created
     * before the rules existed. Such a job never ran under them, so none are applied - the same
     * choice the path layer makes for an empty selection.
     *
     * <p>Every rule is judged from what Git reports. The model's own account of its work is not an
     * input here for the same reason it is not one in the path layers.
     */
    static List<String> brokenRules(
            GuardrailRuleContract.Rules rules, JsonNode... toolResults) {
        if (rules == null) {
            return List.of();
        }
        List<String> broken = new ArrayList<>();
        List<String> changed = changedPaths(toolResults);
        if (!rules.allowNewDependency()) {
            List<String> manifests = changed.stream()
                    .filter(CodingHandlerStageService::isDependencyManifest)
                    .toList();
            if (!manifests.isEmpty()) {
                broken.add("adding a library is not allowed: " + String.join(", ", manifests));
            }
        }
        if (rules.maxChangedFiles() != null && changed.size() > rules.maxChangedFiles()) {
            broken.add("changed " + changed.size() + " files, limit is "
                    + rules.maxChangedFiles());
        }
        if (rules.maxChangedLines() != null) {
            int lines = changedLines(toolResults);
            if (lines > rules.maxChangedLines()) {
                broken.add("changed " + lines + " lines, limit is " + rules.maxChangedLines());
            }
        }
        return List.copyOf(broken);
    }

    /**
     * Whether a path is a file that declares the project's dependencies.
     *
     * <p>Matched on the file name at any depth, so moving the file does not evade the rule. Lock
     * files count: a dependency arrives through them just as surely as through the manifest that
     * names it.
     *
     * <p>The frontend manifest and lock file are already in the fixed Denylist, so allowing new
     * libraries opens the Backend {@code pom.xml} only. That is deliberate - a rule an
     * administrator can switch on must not be able to reopen a fixed one.
     */
    private static boolean isDependencyManifest(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return DEPENDENCY_MANIFESTS.contains(name);
    }

    /**
     * How many lines the diff adds or removes.
     *
     * <p>Counted from the unified diff body, so a header line - {@code +++ b/...} or
     * {@code --- a/...} - is not counted as a change. The tool refuses a diff larger than its cap
     * outright, so the body being counted is never a truncated one.
     */
    private static int changedLines(JsonNode... toolResults) {
        int lines = 0;
        for (JsonNode toolResult : toolResults) {
            JsonNode diff = toolResult.path("diff");
            if (!diff.isTextual()) {
                continue;
            }
            for (String line : diff.textValue().split("\n", -1)) {
                if (line.startsWith("+++") || line.startsWith("---")) {
                    continue;
                }
                if (line.startsWith("+") || line.startsWith("-")) {
                    lines++;
                }
            }
            // Only one tool returns the diff body, and counting a second copy of the same diff
            // would double every number.
            break;
        }
        return lines;
    }

    private static List<String> changedPaths(JsonNode... toolResults) {
        Set<String> changedPaths = new LinkedHashSet<>();
        for (JsonNode toolResult : toolResults) {
            for (JsonNode path : toolResult.path("changedPaths")) {
                if (path.isTextual()) {
                    changedPaths.add(path.textValue());
                }
            }
        }
        return List.copyOf(changedPaths);
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

    private CodingHandlerContract.StageExecutionResponse completePullRequest(
            UUID jobId,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        CodingHandlerContract.HandlerResultResponse requested = latestResult(
                aggregate, "coding.pr_request", "requested");
        requireApproved(aggregate, CodingHandlerContract.ApprovalStage.GITHUB,
                requested.candidateSha(), requested.validationHash());
        if (requested.diffDigest() == null
                || !requested.diffDigest().matches("^sha256:[0-9a-f]{64}$")) {
            throw conflict("The pull request has no approved Diff digest.");
        }
        CodingHandlerResultService.JobRequestIdentity identity = results.jobRequestIdentity(jobId);
        String branch = "system/" + identity.workSlug().substring("system-".length());
        ObjectNode command = objectMapper.createObjectNode();
        command.put("repo", "backend");
        command.put("branch", branch);
        command.put("candidateSha", requested.candidateSha());
        command.put("diffDigest", requested.diffDigest());
        command.put("validationHash", requested.validationHash());
        if (aggregate.workspaceId() == null) {
            throw conflict("The pull request has no bound Coding workspace.");
        }
        command.put("workspaceId", aggregate.workspaceId().toString());
        command.put("title", identity.systemWorkId() + " automated coding change");
        command.put("body", "Automated Coding Job " + identity.systemWorkId()
                + ". Candidate and validation evidence are recorded by the control plane.");
        runner.enqueue(resultId, "CREATE_PR", command);
        CodingRunnerService.TaskOutcome outcome = runner.taskOutcome(resultId, "CREATE_PR");
        if (runnerPending(outcome)) {
            throw runnerPending("Pull request creation is still pending.");
        }
        if (!"SUCCEEDED".equals(outcome.status())) {
            throw new CodingWorkerException(
                    outcome.errorCode() == null ? "PR_CREATION_BLOCKED" : outcome.errorCode(),
                    "Pull request creation was blocked by the host runner.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        JsonNode receipt = outcome.result();
        if (receipt == null
                || !"backend".equals(receipt.path("repository").asText())
                || !"dev".equals(receipt.path("base").asText())
                || !branch.equals(receipt.path("head").asText())
                || !requested.candidateSha().equals(receipt.path("candidateSha").asText())
                || !receipt.path("headSha").asText().matches("^sha1:[0-9a-f]{40}$")
                || !receipt.path("prNumber").canConvertToInt()
                || receipt.path("prNumber").intValue() < 1
                || !receipt.path("prUrl").isTextual()) {
            throw contract("The pull request runner receipt is invalid.");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("repository", "backend");
        payload.put("base", "dev");
        payload.put("head", branch);
        payload.put("candidateSha", requested.candidateSha());
        payload.put("headSha", receipt.path("headSha").asText());
        payload.put("prNumber", receipt.path("prNumber").intValue());
        payload.put("prUrl", receipt.path("prUrl").asText());
        payload.put("state", receipt.path("state").asText("OPEN"));
        return response(resultId, request.handlerKey(), "completed", aggregate.workspaceId(),
                requested.candidateSha(), requested.diffDigest(), requested.validationHash(), payload);
    }

    private CodingHandlerContract.StageExecutionResponse checkDevMerge(
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        CodingHandlerContract.HandlerResultResponse pullRequest = latestResult(
                aggregate, "coding.pr_complete", "completed");
        CodingHandlerContract.HandlerResultResponse deployRequest = latestResult(
                aggregate, "coding.deploy_request", "recorded");
        requireV4DeploymentIdentity(pullRequest, deployRequest);
        requireApproved(aggregate, CodingHandlerContract.ApprovalStage.DEPLOY,
                deployRequest.candidateSha(), deployRequest.validationHash());
        ObjectNode command = objectMapper.createObjectNode();
        command.put("repository", pullRequest.payload().path("repository").asText());
        command.put("prNumber", pullRequest.payload().path("prNumber").asInt());
        command.put("head", pullRequest.payload().path("head").asText());
        command.put("headSha", pullRequest.payload().path("headSha").asText());
        command.put("candidateSha", pullRequest.candidateSha());
        runner.enqueue(resultId, "CHECK_DEV_MERGE", command);
        CodingRunnerService.TaskOutcome outcome = runner.taskOutcome(resultId, "CHECK_DEV_MERGE");
        if (runnerPending(outcome)) {
            throw runnerPending("The dev merge check is still pending.");
        }
        if (!"SUCCEEDED".equals(outcome.status()) || outcome.result() == null) {
            throw new CodingWorkerException(
                    outcome.errorCode() == null ? "DEV_MERGE_CHECK_BLOCKED" : outcome.errorCode(),
                    "The dev merge check failed at the host boundary.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        JsonNode receipt = outcome.result();
        String status = receipt.path("status").asText();
        if (!pullRequest.candidateSha().equals(receipt.path("candidateSha").asText())
                || !pullRequest.payload().path("head").asText()
                        .equals(receipt.path("head").asText())
                || !pullRequest.payload().path("headSha").asText()
                        .equals(receipt.path("headSha").asText())) {
            throw contract("The dev merge runner receipt changed the PR subject.");
        }
        String port = switch (status) {
            case "MERGED" -> "merged";
            case "NOT_MERGED" -> "not_merged";
            case "BLOCKED" -> "blocked";
            default -> throw contract("The dev merge receipt is invalid.");
        };
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("repository", pullRequest.payload().path("repository").asText());
        payload.put("base", "dev");
        payload.put("head", pullRequest.payload().path("head").asText());
        payload.put("prNumber", pullRequest.payload().path("prNumber").asInt());
        payload.put("candidateSha", pullRequest.candidateSha());
        payload.put("headSha", pullRequest.payload().path("headSha").asText());
        payload.put("status", status);
        if ("merged".equals(port)) {
            String mergeSha = receipt.path("mergeSha").asText();
            if (!mergeSha.matches("^sha1:[0-9a-f]{40}$")) {
                throw contract("The merged dev receipt has no valid merge SHA.");
            }
            payload.put("mergeSha", mergeSha);
        }
        putOptional(payload, "reason", receipt.path("reason").textValue());
        return response(resultId, request.handlerKey(), port, aggregate.workspaceId(),
                pullRequest.candidateSha(), pullRequest.diffDigest(),
                pullRequest.validationHash(), payload);
    }

    private CodingHandlerContract.StageExecutionResponse deployRequest(
            UUID jobId,
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        CodingHandlerContract.HandlerResultResponse pullRequest = latestResultOrNull(
                aggregate, "coding.pr_complete", "completed");
        if (pullRequest == null) {
            return sideEffect(resultId, request, aggregate,
                    "recorded", "DEPLOY_REQUEST_RECORDED");
        }
        ObjectNode subject = objectMapper.createObjectNode();
        subject.put("jobId", jobId.toString());
        subject.put("pipelineAttempt", aggregate.pipelineAttempt());
        subject.put("repository", pullRequest.payload().path("repository").asText());
        subject.put("prNumber", pullRequest.payload().path("prNumber").asInt());
        subject.put("candidateSha", pullRequest.candidateSha());
        subject.put("sourceValidationHash", pullRequest.validationHash());
        subject.put("adapterKey", deploymentAdapter.adapterKey());
        subject.put("targetKey", deploymentAdapter.targetKey());
        subject.put("configDigest", deploymentAdapter.configDigest());
        String subjectHash = digest(subject);
        UUID deploymentRequestId = UUID.nameUUIDFromBytes(
                ("deployment-request:" + subjectHash).getBytes(StandardCharsets.UTF_8));
        ObjectNode payload = subject.deepCopy();
        payload.put("deploymentRequestId", deploymentRequestId.toString());
        payload.put("status", "DEPLOY_REQUEST_RECORDED");
        return response(resultId, request.handlerKey(), "recorded", aggregate.workspaceId(),
                pullRequest.candidateSha(), pullRequest.diffDigest(), subjectHash, payload);
    }

    private CodingHandlerContract.StageExecutionResponse deploy(
            UUID resultId,
            CodingHandlerContract.StageExecutionRequest request,
            CodingHandlerContract.AttemptAggregateResponse aggregate) {
        CodingHandlerContract.HandlerResultResponse deployRequest = latestResult(
                aggregate, "coding.deploy_request", "recorded");
        CodingHandlerContract.HandlerResultResponse merge = latestResult(
                aggregate, "coding.dev_merge_check", "merged");
        requireV4DeploymentIdentity(
                latestResult(aggregate, "coding.pr_complete", "completed"), deployRequest);
        requireApproved(aggregate, CodingHandlerContract.ApprovalStage.DEPLOY,
                deployRequest.candidateSha(), deployRequest.validationHash());
        String mergeSha = merge.payload().path("mergeSha").asText();
        String deploymentRequestId = deployRequest.payload()
                .path("deploymentRequestId").asText();
        if (!deploymentAdapter.adapterKey().equals(
                    deployRequest.payload().path("adapterKey").asText())
                || !deploymentAdapter.targetKey().equals(
                    deployRequest.payload().path("targetKey").asText())
                || !deploymentAdapter.configDigest().equals(
                    deployRequest.payload().path("configDigest").asText())) {
            throw conflict("The server deployment adapter changed after approval was requested.");
        }
        if (!mergeSha.matches("^sha1:[0-9a-f]{40}$")
                || !deploymentRequestId.matches("^[0-9a-f-]{36}$")
                || !Objects.equals(deployRequest.candidateSha(), merge.candidateSha())) {
            throw conflict("The deployment evidence is incomplete or stale.");
        }
        UUID executionId = UUID.nameUUIDFromBytes(
                ("deployment-execution:" + deploymentRequestId + ":" + mergeSha)
                        .getBytes(StandardCharsets.UTF_8));
        ObjectNode command = objectMapper.createObjectNode();
        command.put("deploymentRequestId", deploymentRequestId);
        command.put("repository", deployRequest.payload().path("repository").asText());
        command.put("prNumber", deployRequest.payload().path("prNumber").asInt());
        command.put("candidateSha", deployRequest.candidateSha());
        command.put("mergeSha", mergeSha);
        command.put("validationHash", deployRequest.validationHash());
        DeploymentAdapter.DeploymentOutcome outcome = deploymentAdapter.deploy(
                executionId, command);
        if (outcome.status() == DeploymentAdapter.Status.PENDING) {
            throw runnerPending("The allowlisted deployment is still pending.");
        }
        String port = outcome.status() == DeploymentAdapter.Status.COMPLETED
                ? "completed" : "blocked";
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("deploymentRequestId", deploymentRequestId);
        payload.put("deploymentExecutionId", executionId.toString());
        payload.put("adapterKey", deploymentAdapter.adapterKey());
        payload.put("targetKey", deploymentAdapter.targetKey());
        payload.put("configDigest", deploymentAdapter.configDigest());
        payload.put("mergeSha", mergeSha);
        payload.put("status", port.toUpperCase(java.util.Locale.ROOT));
        putOptional(payload, "errorCode", outcome.errorCode());
        if (outcome.payload() != null && outcome.payload().isObject()) {
            payload.set("receipt", outcome.payload().deepCopy());
        }
        return response(resultId, request.handlerKey(), port, aggregate.workspaceId(),
                deployRequest.candidateSha(), deployRequest.diffDigest(),
                deployRequest.validationHash(), payload);
    }

    private static boolean runnerPending(CodingRunnerService.TaskOutcome outcome) {
        return "PENDING".equals(outcome.status()) || "RUNNING".equals(outcome.status());
    }

    private static void requireApproved(
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            CodingHandlerContract.ApprovalStage stage,
            String candidateSha,
            String validationHash) {
        CodingHandlerContract.ApprovalDecisionSummary latest = null;
        for (CodingHandlerContract.ApprovalDecisionSummary decision : aggregate.decisions()) {
            if (decision.stage() == stage) {
                latest = decision;
            }
        }
        if (latest == null
                || latest.decision() != CodingHandlerContract.Decision.APPROVED
                || !Objects.equals(candidateSha, latest.candidateSha())
                || !Objects.equals(validationHash, latest.validationHash())) {
            throw conflict("The required Coding approval is missing or stale.");
        }
    }

    private static void requireV4DeploymentIdentity(
            CodingHandlerContract.HandlerResultResponse pullRequest,
            CodingHandlerContract.HandlerResultResponse deployRequest) {
        if (!deployRequest.payload().hasNonNull("deploymentRequestId")
                || !Objects.equals(pullRequest.candidateSha(), deployRequest.candidateSha())
                || !Objects.equals(pullRequest.payload().path("repository").asText(),
                        deployRequest.payload().path("repository").asText())
                || pullRequest.payload().path("prNumber").asInt(-1)
                        != deployRequest.payload().path("prNumber").asInt(-2)) {
            throw conflict("The deployment request is not bound to the completed pull request.");
        }
    }

    private static CodingWorkerException runnerPending(String message) {
        return new CodingWorkerException(
                "RUNNER_TASK_PENDING", message, HttpStatus.SERVICE_UNAVAILABLE, true, 1_000L);
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
            List<JsonNode> messages,
            JsonNode responseFormat,
            List<ProviderModelRegistration> modelBindings) {
        UUID turnId = UUID.nameUUIDFromBytes(
                (resultId + ":attempt:" + stage.executionAttempt() + ":model:" + turn)
                        .getBytes(StandardCharsets.UTF_8));
        String key = "stage." + hash(
                resultId + ":attempt:" + stage.executionAttempt() + ":model:" + turn);
        List<String> capabilities = "JSON_SCHEMA".equals(
                responseFormat.path("type").asText())
                        ? List.of("CHAT", "STRUCTURED_OUTPUT")
                        : schemas.isEmpty()
                                ? List.of("CHAT")
                                : List.of("CHAT", "TOOL_CALLING");
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
                responseFormat,
                authority.expiresAt());
        CodingModelTurnPermit permit = modelGuard.reserve(authorization, request);
        if (permit.replay()) {
            return permit.cachedResponse();
        }
        try {
            CodingModelTurnContract.Response response = models.execute(request, modelBindings);
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

    private List<ProviderModelRegistration> modelBindings(
            CodingToolService.StageAuthority authority,
            CodingHandlerContract.StageExecutionRequest request,
            ModelUseCase useCase) {
        return profileModelBindings.resolve(
                authority.profileVersionId(), request.nodeId(), request.handlerKey(), useCase);
    }

    private JsonNode outcomeResponseFormat() {
        ObjectNode schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("port").add("payload");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("port").put("type", "string");
        ObjectNode payload = properties.putObject("payload")
                .put("type", "object")
                .put("additionalProperties", false);
        payload.putArray("required").add("summary");
        payload.putObject("properties").putObject("summary").put("type", "string");
        return ProviderResponseFormat.jsonSchema(schema).requestContract();
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
        CodingToolContract.Accepted accepted =
                tools.submitForNode(authorization, request, stage.nodeId());
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

        // The values are quoted because a bare one-word list reads as prose: "port must be
        // completed" was answered with "done", a synonym the stage refuses.
        String ports = switch (handlerKey) {
            case "coding.analyze" -> "\"feasible\" or \"infeasible\"";
            case "coding.code" -> "\"completed\"";
            case "coding.review" -> "\"passed\" or \"changes_requested\"";
            default -> throw contract("The Coding Model stage is not registered.");
        };
        String system = "You are executing " + handlerKey + ". Stay within the supplied request "
                + "and approved tools. When finished, return only JSON with exactly fields port "
                + "and payload. port must be exactly " + ports + ", copied verbatim with no "
                + "synonym or rewording, and payload must be an object. "
                + ("coding.analyze".equals(handlerKey)
                    ? "For this analyze stage, payload must contain exactly the string field "
                        + "summary. "
                    : "")
                + ("coding.code".equals(handlerKey)
                    ? "Use read_diff before any diff-bound tool and again after the final change. "
                    // Without the second sentence the model reads "no apply_patch here"
                    // as "the request cannot be done" and answers infeasible.
                    : "Do not request apply_patch in this stage. A later stage performs "
                        + "the file changes, so judge only whether the request itself "
                        + "can be carried out. ");
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
            schema.put("description", toolDescription(name));
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

    static Set<String> modelTools(
            CodingToolService.StageAuthority authority,
            String nodeId,
            Set<String> stageTools) {
        if (authority.toolBindings().legacy()) {
            return allowedTools(authority.profileAllowedTools(), stageTools);
        }
        return allowedTools(
                authority.toolBindings().modelToolsForNode(nodeId), stageTools);
    }

    private static void requireSystemTools(
            CodingToolService.StageAuthority authority,
            String nodeId,
            Set<String> requiredTools) {
        if (authority.toolBindings().legacy()) return;
        if (!authority.toolBindings().systemToolsForNode(nodeId).equals(requiredTools)) {
            throw forbidden("The Coding node system Tool binding is incomplete.");
        }
    }

    /** Every diff-bound tool is refused until read_diff has established the current diff. */
    private static final String AFTER_READ_DIFF =
            "Call read_diff first in this stage; this tool is refused until the current diff "
                    + "has been read.";

    /**
     * The model only ever sees these words, so a rule it cannot guess belongs here. The
     * patch format is the one the MCP workspace enforces; a diff that misses it is refused
     * as PATCH_POLICY_DENIED after the turn is already spent.
     */
    private static String toolDescription(String name) {
        return switch (name) {
            case "read_file" -> "Read one repository-relative text file.";
            case "search_code" -> "Search the repository for a literal string.";
            case "read_diff" -> "Read the workspace diff as it stands now.";
            case "apply_patch" -> "Apply one unified diff to the workspace. " + AFTER_READ_DIFF
                    + " Every file in the patch starts with a line 'diff --git a/PATH b/PATH' "
                    + "carrying the same repository-relative path twice, then '--- a/PATH', "
                    + "then '+++ b/PATH', then its @@ hunks. Rename, copy, mode and binary "
                    + "diffs are refused.";
            case "run_check" -> "Run one approved check over the changed files. Call it "
                    + "with exactly {\"profile\":\"git-diff-check\"} or "
                    + "{\"profile\":\"python-syntax\"}; an empty argument object is "
                    + "refused. " + AFTER_READ_DIFF;
            case "check_package_allowlist" -> "Check the changed files against the package "
                    + "allowlist. " + AFTER_READ_DIFF;
            case "scan_changed_files" -> "Scan the changed files for forbidden content. "
                    + AFTER_READ_DIFF;
            default -> throw contract("The Coding tool schema is not registered.");
        };
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


    private ObjectNode userMessage(String content) {
        return objectMapper.createObjectNode().put("role", "user").put("content", content);
    }

    /**
     * Replays a refused call's sentence without the call itself. The refused call never
     * ran, so pairing it with a tool result would describe an exchange that did not
     * happen, and an assistant message may not carry an unanswered tool call.
     */
    private ObjectNode plainAssistantMessage(CodingModelTurnContract.Response response) {
        String content = response.assistant().content();
        return objectMapper.createObjectNode()
                .put("role", "assistant")
                .put("content", content.isBlank() ? "(a tool call that was refused)" : content);
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

    private ModelOutcome parseOutcome(String handlerKey, String raw, Set<String> ports) {
        StructuredOutputGuard.ValidatedOutput<String> validated;
        try {
            validated = STRUCTURED_OUTPUT_GUARD.validateOrRepair(
                    raw,
                    candidate -> readOutcome(candidate, ports) != null,
                    StructuredOutputGuard::extractOutermostJsonObject);
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

    private ModelOutcome parseOutcome(JsonNode value, Set<String> ports) {
        if (value == null || !value.isObject() || value.size() != 2
                || !value.path("port").isTextual()
                || !ports.contains(value.path("port").asText())
                || !value.path("payload").isObject()) {
            throw contract("The Coding Model stage result is invalid.");
        }
        return new ModelOutcome(
                value.path("port").asText(), value.path("payload").deepCopy());
    }

    /** Returns null when the text is not the declared stage result object. */
    private ModelOutcome readOutcome(String raw, Set<String> ports) {
        if (raw == null) {
            return null;
        }
        JsonNode value = StructuredOutputGuard.readSingleJsonObject(objectMapper, raw);
        if (value == null || value.size() != 2
                || !value.path("port").isTextual()
                || !ports.contains(value.path("port").asText())
                || !value.path("payload").isObject()) {
            return null;
        }
        return new ModelOutcome(
                value.path("port").asText(), value.path("payload").deepCopy());
    }

    private static CodingHandlerContract.HandlerResultResponse latestResult(
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            String handlerKey,
            String port) {
        CodingHandlerContract.HandlerResultResponse result = latestResultOrNull(
                aggregate, handlerKey, port);
        if (result != null) {
            return result;
        }
        throw conflict("The Coding stage prerequisite result is missing.");
    }

    private static CodingHandlerContract.HandlerResultResponse latestResultOrNull(
            CodingHandlerContract.AttemptAggregateResponse aggregate,
            String handlerKey,
            String port) {
        for (int index = aggregate.results().size() - 1; index >= 0; index--) {
            CodingHandlerContract.HandlerResultResponse result = aggregate.results().get(index);
            if (handlerKey.equals(result.handlerKey()) && port.equals(result.resultPort())) {
                return result;
            }
        }
        return null;
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
            case "coding.pr_complete" -> CodingHandlerContract.ResultType.PULL_REQUEST;
            case "coding.dev_merge_check" -> CodingHandlerContract.ResultType.DEV_MERGE;
            case "coding.deploy_request" -> CodingHandlerContract.ResultType.DEPLOY_REQUEST;
            case "coding.deploy" -> CodingHandlerContract.ResultType.DEPLOYMENT;
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
                            .digest(objectMapper.writeValueAsBytes(canonical(value))));
        }
        catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 or JSON encoding is unavailable.", failure);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            java.util.TreeSet<String> fields = new java.util.TreeSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> sorted.set(field, canonical(value.get(field))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            value.forEach(item -> ordered.add(canonical(item)));
            return ordered;
        }
        return value.deepCopy();
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
