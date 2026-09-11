package org.urizo.axmodulestudio.backend.cms.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformClient;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformException;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileModelBindingService;

@Service
@Profile("dev & local-full")
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class NaturalCmsStageService {

    private static final Logger log = LoggerFactory.getLogger(NaturalCmsStageService.class);

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
        NaturalCmsStore.RuntimePolicy toolPolicy = store.runtimePolicy(
                authorization, job.profileVersionId());

        if ("cms.apply".equals(request.handlerKey())) {
            JsonNode approvedCommand = revalidateApply(job, request, toolPolicy);
            UUID actorId = store.actorId(authorization, jobId);
            NaturalCmsContract.HandlerResult stored = store.recordApplied(
                    authorization,
                    jobId,
                    pipelineAttempt,
                    resultId,
                    request.expectedStateVersion(),
                    () -> apply(job, request, resultId, approvedCommand, actorId));
            return response(stored);
        }

        NaturalCmsContract.StageExecutionResponse executed = switch (request.handlerKey()) {
            case "cms.analyze" -> analyze(job, request, resultId);
            case "cms.preview" -> preview(job, request, resultId, toolPolicy);
            case "cms.discard" -> discard(job, request, resultId, toolPolicy);
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
            NaturalCmsStore.RuntimePolicy toolPolicy) {
        requireStatus(job, "ACTIVE");
        ObjectNode currentState = resources.snapshot(job.resource());
        Set<String> allowedTools = nodeTools(
                toolPolicy, stage.nodeId(),
                Set.of("validate_cms_command"),
                Set.of("resolve_cms_target", "create_cms_preview"));
        List<JsonNode> schemas = previewToolSchemas(job.resource());
        List<ProviderModelRegistration> modelBindings =
                modelBindings(job, stage, ModelUseCase.TOOL_CALL);
        CodingModelTurnContract.Response response = modelTurn(
                job, stage, resultId, 1, schemas,
                initialMessages(job, currentState, true), modelBindings);
        if (response.toolCalls().size() != 1
                || !"validate_cms_command".equals(response.toolCalls().get(0).name())) {
            throw contract("Natural CMS Model must return one validate_cms_command Tool Call.");
        }
        JsonNode command = validatedCommand(
                job, response.toolCalls().get(0).arguments().path("command"));

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
            NaturalCmsStore.RuntimePolicy toolPolicy) {
        requireDecision(job, "REJECTED");
        Set<String> allowedTools = nodeTools(
                toolPolicy, stage.nodeId(), Set.of(), Set.of("discard_cms_preview"));
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
            NaturalCmsContract.StageExecutionRequest stage,
            NaturalCmsStore.RuntimePolicy toolPolicy) {
        requireDecision(job, "APPROVED");
        Set<String> allowedTools = nodeTools(
                toolPolicy, stage.nodeId(), Set.of(),
                Set.of("revalidate_cms_preview", "apply_cms_preview"));
        JsonNode command = validatedCommand(job, job.structuredCommand());
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
            JsonNode command,
            UUID actorId) {
        JsonNode applied;
        if ("TEMPLATE".equals(job.resource().type())) {
            JsonNode preview = job.preview();
            if (preview == null || !preview.path("previewId").asText().equals(job.previewId().toString())
                    || !preview.path("previewHash").asText().equals(job.previewHash())
                    || !preview.path("resource").equals(objectMapper.valueToTree(job.resource()))
                    || !preview.path("command").equals(command)) {
                throw contract("승인한 템플릿 미리보기와 명령이 일치하지 않습니다.");
            }
            applied = resources.applyApprovedTemplate(job.resource(), command, actorId,
                    job.requestText(), preview.path("before"));
        } else {
            applied = resources.apply(job.resource(), command, actorId);
        }
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
        CodingModelTurnContract.Request request = new CodingModelTurnContract.Request(
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
                clock.instant().plusSeconds(60));
        try (ModelObservationScope ignored = ModelObservationScope.open(
                job.jobId(), job.traceId(), job.profileVersionId(), stage.nodeId())) {
            return models.executeNaturalCms(request, modelBindings);
        }
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
            if ("TEMPLATE".equals(job.resource().type())) {
                TemplateCommandPolicy.FIELDS.stream().sorted().forEach(editableFields::add);
            } else {
                currentState.fieldNames().forEachRemaining(name -> {
                    if (!RESOURCE_METADATA_FIELDS.contains(name)) editableFields.add(name);
                });
            }
        }
        if (job.approvalFeedback() != null) {
            context.put("approvalFeedback", job.approvalFeedback());
        }
        ObjectNode reference = "TEMPLATE".equals(job.resource().type())
                ? resources.templateReference(job.resource(), job.requestText())
                : resources.promptContext(job.resource());
        if (reference != null && !reference.isEmpty()) {
            context.set("reference", reference);
        }
        String instruction = commandStage
                ? commandInstruction(job.resource())
                : feasibilityInstruction(job.resource());
        return List.of(
                objectMapper.createObjectNode().put("role", "system").put("content", instruction),
                objectMapper.createObjectNode().put("role", "user")
                        .put("content", encode(context)));
    }

    /**
     * 리소스마다 열린 operation이 다르므로 지시문도 갈린다.
     *
     * <p>메뉴(`AI05-006`·`AI05-007`), 게시판·게시물(`AI05-014`), 컨텐츠(`AI05-015`)는
     * {@code CREATE}·{@code DELETE}까지 열려 있어 첫 문장이 다르다. 남은 템플릿은 `AI05-013`이 정한
     * {@code UPDATE} 문구를 그대로 쓰고 공통 규칙만 덧붙인다.
     *
     * <p>Tool Call 한 번으로 명령을 받는 구조는 `AI05-013`을 그대로 따른다. Tool Schema의
     * {@code operation}은 자유 문자열이라 이 지시문만으로 세 operation을 모두 표현할 수 있다.
     */
    private static String commandInstruction(NaturalCmsContract.ResourceRef resource) {
        String resourceType = resource.type();
        String changedFieldsOnly = " Send only the fields the request changes. Every field you"
                + " send is written and a field you leave out keeps its current value, so never"
                + " repeat a value that is already correct.";
        String emptyDelete =
                " DELETE carries no fields and its fields object stays empty.";
        if (NaturalCmsResourceService.isPost(resource)) {
            return commandInstruction(new NaturalCmsContract.ResourceRef("CONTENT", "new"))
                    .replace("Create one CONTENT command", "Create one POST command")
                    + " The post stays in its board; never send a board field."
                    + " thumbnailImageId is a separate CMS image id, not a body image."
                    + " Take thumbnail ids only from the current thumbnail, body images or attached image URLs."
                    + " regionCodeId and categoryCodeId come only from reference.codes in the board's matching group."
                    + " Never invent ids or create codes. Null explicitly clears an optional image or classification.";
        }
        if ("BOARD".equals(resourceType)) {
            return "Create one BOARD command with operation CREATE, UPDATE or DELETE. "
                    + "Call validate_cms_command exactly once with that command. "
                    + "fields may use only names from editableFields."
                    + changedFieldsOnly
                    + " CREATE sends at least name and leaves description out when the request"
                    + " does not give one."
                    + emptyDelete
                    + " A board that still has posts cannot be deleted."
                    + " displayType is LIST or CARD. regionGroupKey and categoryGroupKey are optional"
                    + " keys from reference.codeGroups only. Never create or invent groups.";
        }
        if ("CONTENT".equals(resourceType)) {
            // 본문이 Tiptap Document(JSON)다. 모델이 트리를 지어내지 않도록 현재 문서를 고쳐
            // 쓰게 하고, 쓸 수 있는 부품을 이름으로 못박는다. 틀린 구조는 서버가 거부한다.
            return "Create one CONTENT command with operation CREATE, UPDATE or DELETE. "
                    + "Call validate_cms_command exactly once with that command. "
                    + "fields may use only names from editableFields."
                    + changedFieldsOnly
                    + " The body field is a ProseMirror document serialised as a JSON string,"
                    + " the same shape currentState.body already has. Start from that document,"
                    + " change only the parts the request asks for and keep everything else"
                    + " exactly as it is, then send the whole document back as one JSON string."
                    + " A document is {\"type\":\"doc\",\"content\":[...]} and its nodes may only"
                    + " be paragraph, heading, bulletList, orderedList, listItem, text, image,"
                    + " hardBreak, blockquote and horizontalRule; a text node may carry bold,"
                    + " italic, strike, underline, textStyle, highlight or link marks."
                    + " A horizontalRule node has no content."
                    + " Colour is written as {\"type\":\"textStyle\",\"attrs\":{\"color\":\"#c0392b\"}}"
                    + " for text and {\"type\":\"highlight\",\"attrs\":{\"color\":\"#fff3a3\"}} for a"
                    + " marker pen. Only these colours exist: text #6b7d84 grey, #c0392b red,"
                    + " #1d6fb8 blue, #2a7d55 green, #c1701a orange; marker #fff3a3 yellow,"
                    + " #d5f2dd green, #d8ecfb blue, #fbdce8 pink, #e6ebed grey."
                    + " Never invent another colour."
                    + " A heading uses attrs.level 2 or 3."
                    + " A picture is an image node, never a link and never plain text: write"
                    + " {\"type\":\"image\",\"attrs\":{\"src\":\"...\",\"alt\":\"short description\"}}"
                    + " as its own node in content."
                    + " An image src must be one that already appears in currentState.body or that"
                    + " the request text lists as an attached image. When the request attaches an"
                    + " image, place it as an image node with that exact src. Never invent a src."
                    + " A mark is written as {\"type\":\"bold\"} or"
                    + " {\"type\":\"link\",\"attrs\":{\"href\":\"...\"}}; never use the mark name as"
                    + " the key."
                    + " CREATE sends title and body, and its body is a new document."
                    + emptyDelete;
        }
        if ("TEMPLATE".equals(resourceType)) {
            return "Create one TEMPLATE UPDATE command. "
                    + "Call validate_cms_command exactly once with that command. "
                    + "fields may use only names from editableFields."
                    + changedFieldsOnly
                    + " The selected resource.id is fixed. Never select another template or modify its key."
                    + " layout is CLASSIC, MINIMAL or BOLD; primaryColor is #RRGGBB."
                    + " heroButtonUrl is empty or one of reference.buttonPaths; no new menus or pages."
                    + " heroImages is an ordered array of at most five objects, each with exactly"
                    + " url, title and description as strings. Its first item is the representative image."
                    + " Start from currentState.heroImages, preserve every unrequested photo and caption,"
                    + " and send the complete resulting array only when images change."
                    + " Keep title and description with their image when reordering. Empty strings clear captions;"
                    + " an empty array unlinks all photos but never deletes uploaded files."
                    + " Use only exact URLs from reference.availableImageUrls. Attached photos were already"
                    + " uploaded by the user, so adding or replacing them is supported. Never invent image URLs."
                    + " Image titles are at most 120 characters; descriptions at most 240."
                    + " siteName, active, main-site template selection and public paths are read-only."
                    + " Never change menus, codes, content, boards, roles, source code or guardrails."
                    + " No raw HTML, CSS or JavaScript; wording is plain text.";
        }
        if (!"MENU".equals(resourceType)) {
            return "Create one " + resourceType + " UPDATE command. "
                    + "Call validate_cms_command exactly once with the UPDATE command. "
                    + "fields may use only names from editableFields."
                    + changedFieldsOnly;
        }
        return "Create one MENU command with operation CREATE, UPDATE or DELETE. "
                + "Call validate_cms_command exactly once with that command. "
                + "fields may use only names from editableFields."
                + changedFieldsOnly
                + " Renaming sends name alone. Linking sends targetType and targetId alone and"
                + " never changes the name. A path starts with /. parentId is null for a top menu."
                + " position is the 1-based place among menus that share the same parentId; never"
                + " send displayOrder and never compute menu numbers."
                + " targetType is NONE, CONTENT or BOARD, and targetId is null unless the type is"
                + " CONTENT or BOARD."
                + " CREATE sends at least name, path and parentId."
                + emptyDelete
                + " Take every id from the reference lists and never invent one.";
    }

    /**
     * 화면이 다루는 범위를 알려주지 않으면 범위 밖 요청도 feasible로 판정한다.
     *
     * <p>실제로 메뉴 화면에서 게시글 등록 요청이 통과해 명령 단계에서 계약 밖 형식으로 멈췄다.
     * 무엇을 바꿀 수 있는 화면인지와 무엇이 범위 밖인지를 함께 준다.
     */
    private static String feasibilityInstruction(NaturalCmsContract.ResourceRef resource) {
        String scope = "the selected content's title and body only";
        String excluded = "writing posts, editing article bodies, templates and members";
        /** 리소스별로 덧붙이는 단서. 범위 문장에 섞으면 조건이 전체로 번진다. */
        String note = "";
        if ("MENU".equals(resource.type())) {
            scope = "menus only: a menu's name, path, parent, order among siblings, and which "
                    + "content or board it links to. Creating and deleting a menu is included";
        }
        else if (NaturalCmsResourceService.isPost(resource)) {
            // 게시물 화면에서는 글쓰기가 범위 안이다. 공통 문구를 그대로 쓰면 전부 거부된다.
            scope = "the posts of the selected board: writing a new post and changing or "
                    + "deleting a post's title and rich-text body, using supported headings, lists,"
                    + " quotes, dividers, bold, italic, strike, underline, colours and links;"
                    + " placing, moving or removing already uploaded body images and a separate thumbnail,"
                    + " and choosing region/category codes from reference.codes."
                    + " Attached images are already uploaded, so asking to upload or add them is included";
            excluded = "changing the board itself, menus, static content pages, templates "
                    + "and members, finding unattached images, inventing or creating codes";
        }
        else if ("BOARD".equals(resource.type())) {
            scope = "boards only: creating a board, changing a board's name, description, LIST/CARD display type or existing code group bindings, "
                    + "and deleting a board";
            excluded = "writing or editing posts, menus, static content pages, templates "
                    + "and members";
            // 조건을 삭제에만 묶고 나머지는 무관함을 명시한다. 한 문장에 붙여 두었더니
            // 모델이 posts가 0이 아니면 이름 변경까지 막았다.
            note = " reference.posts is how many posts this board has and it restricts "
                    + "deletion only. Deleting is infeasible when it is not 0, and that "
                    + "reason says the board still has that many posts. Creating a board and "
                    + "changing a name or description stay feasible whatever reference.posts "
                    + "is.";
        }
        else if ("CONTENT".equals(resource.type())) {
            // 컨텐츠 삭제에는 조건이 없다. 삭제하면 그 컨텐츠를 연결한 메뉴가 `연결 없음`이 될 뿐이고
            // 그 정리는 기존 CMS가 한다. 조건이 없으니 판단할 참고 값도 주지 않는다.
            //
            // 이미지는 사람이 올린다. 화면이 먼저 올려 요청에 주소를 실어 주므로 그 사진을
            // 넣는 것도 범위 안이다. 모델이 어디선가 가져오는 것만 범위 밖이다.
            //
            // 첨부한 사진을 `올려줘`라고 하면 반려됐다. 범위에 `놓기`만 있어 모델이 업로드를
            // 화면 밖 일로 읽었다. 사람이 쓰는 말과 실제 하는 일을 이어 준다.
            //
            // `밑줄 적용해서 작성해줘`도 같은 이유로 반려됐다. 범위에 `제목과 본문을 바꾼다`까지만
            // 있고 본문에 무엇을 쓸 수 있는지가 없어, 서식을 지정한 요청을 화면 밖으로 읽었다.
            // 36초 뒤 같은 일을 `밑줄 적용해줘`로 짧게 쓰니 통과했다. 쓸 수 있는 것을 적어 둔다.
            scope = "static content pages only: creating a content page, changing the selected "
                    + "page's title and body, and deleting the selected page. A body may use"
                    + " headings, bullet and numbered lists, quotes, dividers, bold, italic,"
                    + " strike, underline, text colour, a marker pen, links and images, so asking"
                    + " for any of those while writing or editing"
                    + " the body is part of this screen. Placing, moving or "
                    + "removing an image the body already contains or the request attaches is "
                    + "included. An attached image has already been uploaded, so asking to "
                    + "upload, put up or add it means placing it in the body and stays feasible";
            excluded = "finding an image that was neither attached nor already in the body, "
                    + "writing posts, boards, menus, templates and members";
        }
        else if ("TEMPLATE".equals(resource.type())) {
            scope = "only the selected template identified by resource.id: layout (CLASSIC, MINIMAL, BOLD),"
                    + " primary colour, Header and Footer text, main title and description, button text and"
                    + " an existing path from reference.buttonPaths, and up to five images with each image's"
                    + " title, short description, order, replacement or unlinking. Photos already in the"
                    + " template or explicitly attached to this request are available; attached photos have"
                    + " already been uploaded, so asking to add or upload them means placing them here";
            excluded = "other templates, creating or deleting templates, menus, codes, content pages,"
                    + " boards or posts, members or roles, siteName or public site paths, switching the"
                    + " main site's template, source code, guardrails, arbitrary HTML/CSS/JavaScript,"
                    + " image generation, web image search and unattached images";
            note = " Reject the whole request when it also asks for an excluded change; never silently"
                    + " perform only the allowed part. Distinguish a requested change from plain wording:"
                    + " mentioning menus or codes inside a banner title does not change those resources."
                    + " Requests to edit a different template are infeasible even if its fields would be valid.";
        }
        return "Decide whether this request can be done on this screen. Return only JSON with "
                + "exactly fields port and payload; port must be feasible or infeasible and "
                + "payload must be an object. This screen changes " + scope + ". "
                + "Anything else is infeasible even when it sounds related, including "
                + excluded + ". When the port is "
                + "infeasible put a short Korean sentence in payload.reason saying what this "
                + "screen cannot do. A request this screen can do stays feasible even when it "
                + "needs several fields or a confirmation."
                + note;
    }

    /**
     * 거부된 명령을 로그에 남기고 그대로 다시 던진다.
     *
     * <p>명령 단계 예외는 Handler 결과로 기록되지 않아 Job이 {@code ACTIVE}로 남고 화면은
     * `미리보기를 받지 못했습니다`로 끝난다. 무엇이 왜 거부됐는지 남는 곳이 없으면 원인을 찾을
     * 방법이 없다. 명령서에는 관리자가 쓴 CMS 내용만 들어 있고 Secret은 없다.
     */
    private JsonNode validatedCommand(
            NaturalCmsContract.JobResponse job, JsonNode proposal) {
        try {
            return "TEMPLATE".equals(job.resource().type())
                    ? resources.validateCommand(job.resource(), proposal, job.requestText())
                    : resources.validateCommand(job.resource(), proposal);
        }
        catch (NaturalCmsException failure) {
            String command = encode(proposal);
            log.warn("Natural CMS rejected a model command: jobId={} resource={}:{} code={} "
                            + "reason={} command={}",
                    job.jobId(), job.resource().type(), job.resource().id(),
                    failure.code(), failure.getMessage(),
                    command.length() > 2000 ? command.substring(0, 2000) + "…" : command);
            throw failure;
        }
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

    private List<JsonNode> previewToolSchemas(NaturalCmsContract.ResourceRef resource) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("name", "validate_cms_command");
        schema.put("description", "Submit one Natural CMS UPDATE command.");
        schema.put("schemaDigest", NaturalCmsToolContract.MODEL_TOOL_SCHEMA_DIGESTS
                .get("validate_cms_command"));
        ObjectNode input = schema.putObject("inputSchema");
        input.put("type", "object");
        input.put("additionalProperties", false);
        ObjectNode command = input.putObject("properties")
                .putObject("command");
        command.put("type", "object");
        command.put("additionalProperties", false);
        ObjectNode commandProperties = command.putObject("properties");
        commandProperties.putObject("operation").put("type", "string");
        commandProperties.putObject("fields").put("type", "object");
        if ("TEMPLATE".equals(resource.type())) {
            ((ObjectNode) commandProperties.path("operation")).putArray("enum").add("UPDATE");
            commandProperties.set("fields", TemplateCommandPolicy.fieldSchema(objectMapper));
        }
        command.putArray("required").add("operation").add("fields");
        input.putArray("required").add("command");
        return List.of(schema);
    }

    static Set<String> allowedTools(Set<String> profileTools, Set<String> stageTools) {
        Set<String> allowed = new java.util.HashSet<>(stageTools);
        allowed.retainAll(profileTools);
        return Set.copyOf(allowed);
    }

    private static Set<String> nodeTools(
            NaturalCmsStore.RuntimePolicy policy,
            String nodeId,
            Set<String> requiredModelTools,
            Set<String> requiredSystemTools) {
        if (policy.toolBindings().legacy()) {
            Set<String> required = new java.util.HashSet<>(requiredModelTools);
            required.addAll(requiredSystemTools);
            if (!policy.allowedTools().containsAll(required)) {
                throw new NaturalCmsException(
                        "TOOL_NOT_ALLOWED",
                        "Natural CMS runtime policy rejected the Tool.",
                        HttpStatus.FORBIDDEN);
            }
            return policy.allowedTools();
        }
        if (!policy.toolBindings().modelToolsForNode(nodeId).equals(requiredModelTools)
                || !policy.toolBindings().systemToolsForNode(nodeId)
                .equals(requiredSystemTools)) {
            throw new NaturalCmsException(
                    "TOOL_NOT_ALLOWED",
                    "Natural CMS node Tool binding is incomplete.",
                    HttpStatus.FORBIDDEN);
        }
        return policy.toolBindings().toolsForNode(nodeId);
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
