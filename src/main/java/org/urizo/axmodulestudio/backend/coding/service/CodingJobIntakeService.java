package org.urizo.axmodulestudio.backend.coding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

/**
 * Turns what a general administrator actually types into a Coding Job.
 *
 * <p>{@code CreateCodingJobRequest} asks for thirteen fields, and a screen where someone writes
 * "회원 목록에 가입일도 보이게 해줘" can supply exactly two of them. The rest is the server's job,
 * and one of them cannot be invented: the runner checks the work folder out at {@code baseSha},
 * so a well-formed but fictional sha fails at the first command rather than at validation.
 *
 * <p>Spring runs in a container with no git, so the sha is asked of the runner and waited for.
 * The wait is short because the runner resolves an already-fetched ref rather than reaching the
 * network; the only real delay is its two-second poll. Waiting also buys the console an honest
 * failure: when nobody has started the runner, the request says so immediately instead of
 * creating a Job that would sit in the queue forever.
 */
@Service
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class CodingJobIntakeService {

    private static final String SCAN_KIND = "PREPARE_SCAN_WORKTREE";
    private static final String WORKTREE_KIND = "CREATE_WORKTREE";
    private static final String PROFILE_KEY = "LLM_OPS";
    private static final String GRAPH_STEP = "start";
    private static final String PROMPT_VERSION = "coding-plan-v1";
    private static final Duration JOB_LIFETIME = Duration.ofHours(1);
    private static final int MAX_REQUEST_CHARACTERS = 10_000;
    private static final Duration CLASSIFY_DEADLINE = Duration.ofSeconds(30);

    /**
     * The classifier's whole instruction. firstText/secondText are shown to the requester and
     * become the two Jobs' request sentences, so they are written in the requester's register -
     * a wrong word here surfaces on the screen verbatim.
     */
    private static final String CLASSIFY_PROMPT = """
            당신은 CMS 개발 요청의 접수 분류기입니다. 요청 문장을 읽고 다음 중 하나로만             판정합니다. server: 저장되는 값, 목록·조회 응답에 담기는 내용, 계산·검증 규칙 등             눈에 보이지 않는 동작을 바꾸는 요청. screen: 화면에 보이는 칸·순서·제목·문구·배치 등             이미 내려오는 내용을 보여주는 방식만 바꾸는 요청. both: 화면에 새로 보여야 하는 값이             있는데 그 값을 내려주는 동작도 함께 만들어야 하는 요청. 확신이 없으면 both 가 아니라             더 그럴듯한 한쪽을 고릅니다. both 일 때만 firstText 에 값을 준비하는 일을,             secondText 에 화면에 보여주는 일을 각각 요청자가 쓴 말투 그대로 한 문장씩 씁니다.             개발 용어, 파일명, 저장소 이름을 절대 쓰지 않습니다. both 가 아니면 firstText 와             secondText 는 빈 문자열로 둡니다.""";

    /**
     * All three, including STRUCTURED_OUTPUT. A Job that omits it is accepted and then refused
     * at the first model turn, which reads as an unrelated failure much later.
     */
    private static final List<String> CAPABILITIES =
            List.of("CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT");

    /**
     * A placeholder. Nothing joins on it today and no project row exists to point at; it is
     * stable so two Jobs group together if a real registry ever arrives. The repository is no
     * longer one of these: it decides which checkout the build stage asks for, so it is read
     * back rather than assumed. See {@link CodingRepositories}.
     */
    private static final UUID PROJECT_ID =
            UUID.fromString("bc3a6b45-d6a8-4bf1-8932-bcd6989de304");

    /** The runner reports a bare 40-character sha; the Job contract wants it prefixed. */
    private static final Pattern BARE_SHA1 = Pattern.compile("^[0-9a-f]{40}$");

    /**
     * Counted in polls rather than measured against the clock. A deadline computed from the
     * injected Clock never arrives when that Clock is fixed, which is exactly how a test
     * supplies one; the loop then spins forever. Sixty half-second polls is the same thirty
     * seconds and cannot depend on time appearing to pass.
     */
    private final GuardrailPathSelectionService guardrailSelections;
    // A provider rather than the service itself: the model-turn bridge rides its own switch
    // (ax.coding.model-turn-bridge), and an app booted without it must still boot.
    private final ObjectProvider<CodingModelTurnService> modelTurns;
    private final int maxShaPolls;
    private final Duration shaPollInterval;

    private final CodingHandlerCommandService commands;
    private final CodingRunnerService runner;
    private final ProfileVersionRepository profileVersions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Two constructors mean Spring cannot guess, and without this it looks for a no-arg one and
     * fails the whole context at startup. The second constructor exists so a test can shorten
     * the sha poll; production takes these defaults.
     */
    @Autowired
    CodingJobIntakeService(
            CodingHandlerCommandService commands,
            CodingRunnerService runner,
            ProfileVersionRepository profileVersions,
            GuardrailPathSelectionService guardrailSelections,
            ObjectProvider<CodingModelTurnService> modelTurns,
            ObjectMapper objectMapper,
            Clock clock) {
        this(commands, runner, profileVersions, guardrailSelections, modelTurns, objectMapper,
                clock, 60, Duration.ofMillis(500));
    }

    CodingJobIntakeService(
            CodingHandlerCommandService commands,
            CodingRunnerService runner,
            ProfileVersionRepository profileVersions,
            GuardrailPathSelectionService guardrailSelections,
            ObjectProvider<CodingModelTurnService> modelTurns,
            ObjectMapper objectMapper,
            Clock clock,
            int maxShaPolls,
            Duration shaPollInterval) {
        this.commands = Objects.requireNonNull(commands, "commands are required");
        this.runner = Objects.requireNonNull(runner, "runner is required");
        this.profileVersions = Objects.requireNonNull(
                profileVersions, "profileVersions are required");
        this.guardrailSelections = Objects.requireNonNull(
                guardrailSelections, "guardrailSelections are required");
        this.modelTurns = Objects.requireNonNull(modelTurns, "modelTurns are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.maxShaPolls = maxShaPolls;
        this.shaPollInterval = shaPollInterval;
    }

    public CodingConsoleContract.CreateJobOutcome create(
            AuthenticatedActor actor,
            UUID traceId,
            String idempotencyKey,
            CodingConsoleContract.CreateJobRequest body) {
        String repository = body == null ? null : body.repository();
        String requestText = body == null || body.requestText() == null
                ? null : body.requestText().strip();
        if (requestText == null || requestText.isEmpty()) {
            throw failure("CODING_REQUEST_TEXT_REQUIRED",
                    "요청 내용을 입력해 주세요.", HttpStatus.BAD_REQUEST);
        }
        if (requestText.length() > MAX_REQUEST_CHARACTERS) {
            throw failure("CODING_REQUEST_TEXT_TOO_LONG",
                    "요청 내용이 너무 깁니다.", HttpStatus.BAD_REQUEST);
        }

        // The screen no longer asks which side the sentence is about - "가입일도 보이게 해줘"
        // has no answer the writer could know, since it needs both. An empty repository means
        // "read the sentence". The field still arrives filled on the second leg of a split,
        // where the answer was decided by the classifier on the first.
        CodingConsoleContract.SplitPlan split = null;
        if (repository == null || repository.isBlank()) {
            Classification verdict = classify(traceId, idempotencyKey, requestText);
            repository = verdict.repository();
            if (verdict.split() != null) {
                // Not confirmed with the requester: they cannot judge the split, and the plan
                // approval remains the place to say no. The data part simply starts, and the
                // screen is told - in the classifier's phrasing of the requester's own words -
                // what runs now and what follows.
                split = verdict.split();
                requestText = split.firstText();
            }
        }
        if (!CodingRepositories.isKnown(repository)) {
            // The name decides which checkout the runner prepares and which services the build
            // stage names, so an unknown one has to stop here: every later stage would ask for
            // a work folder that was never created.
            throw failure("CODING_REPOSITORY_NOT_SUPPORTED",
                    "요청할 수 있는 저장소가 아닙니다.", HttpStatus.BAD_REQUEST);
        }
        // The fence names folders somewhere but none in this repository, so every change a
        // request here could make lands outside it. Nothing about the sentence can alter that,
        // and the analyst has already been seen to wave such a request through: with no allowed
        // folder to draw from, the file list it is given is empty and the strongest signal it
        // had is gone. Refused here, before a model is asked anything, because the answer is
        // already decided. Guide 6-7 asks for exactly this - stopped at the request.
        if (guardrailSelections.closedTo(repository)) {
            throw failure("CODING_GUARDRAIL_REPOSITORY_CLOSED",
                    "요청하신 영역은 지금 울타리에서 열려 있지 않습니다. "
                            + "최고관리자에게 울타리 설정을 요청해 주세요.",
                    HttpStatus.CONFLICT);
        }
        ProfileVersionRepository.AdminStoredProfileVersion profile = activeProfile();
        List<String> nodes = nodeIds(profile.snapshot());
        if (!nodes.contains(GRAPH_STEP)) {
            // The Job authority refuses a graphStep outside allowedNodes, so this would be
            // rejected on the very first stage with a message about scope rather than shape.
            throw failure("CODING_PROFILE_START_NODE_MISSING",
                    "활성 AI 설정에 시작 노드가 없습니다.", HttpStatus.CONFLICT);
        }

        ScanResult scan = scan(repository);
        String baseSha = scan.baseSha();
        Instant now = Instant.now(clock);

        CodingHandlerContract.CreateCodingJobRequest request =
                new CodingHandlerContract.CreateCodingJobRequest(
                        "1.0",
                        profile.profileVersionId(),
                        PROJECT_ID,
                        CodingRepositories.identifierOf(repository),
                        GRAPH_STEP,
                        baseSha,
                        digest("context", requestText, baseSha),
                        digest("policy", profile.profileVersionId().toString(), baseSha),
                        PROMPT_VERSION,
                        CAPABILITIES,
                        nodes,
                        now.plus(JOB_LIFETIME),
                        requestText);
        // The file list travels with the creation itself: the guardrail copy is written
        // once, in that transaction, and no runtime account may update it afterwards.
        CodingHandlerContract.CreateCodingJobResponse created =
                commands.create(actor, traceId, idempotencyKey, request, scan.files());
        // The code stage's MCP tools operate inside a workspace the host runner prepares,
        // and nothing else in the product flow asks for one - the 8/31 walkthrough inserted
        // this task by hand, which is why every Job died at its first tool call with
        // "workspace not found" once the screen replaced the manual procedure. Queued here,
        // fire-and-forget: the plan and its approval give the runner minutes of head start.
        runner.enqueue(WORKTREE_KIND, objectMapper.createObjectNode()
                .put("repo", repository)
                .put("baseSha", baseSha.substring("sha1:".length()))
                .put("workspaceId", created.job().jobId().toString()));
        return new CodingConsoleContract.CreateJobOutcome("1.0", created, split);
    }

    /** What the classifier decided: the one repository, or a split whose first leg is it. */
    private record Classification(String repository, CodingConsoleContract.SplitPlan split) { }

    /**
     * Reads the sentence and answers which side it is about - the question the screen used to
     * ask the requester, who could not know the answer.
     *
     * <p>One structured model turn, no tools, strict schema. The model is picked from the
     * capability registry rather than the profile: the profile binds models to graph nodes and
     * this call happens before any Job or graph exists. When the request needs both sides, the
     * classifier also writes the two part-sentences, in the requester's own register - those
     * are what the screen shows, so they must never contain system words.
     *
     * <p>A classification that cannot be obtained fails the submission honestly. Guessing a
     * side instead would send the model into a checkout without the files it needs, and the
     * requester would learn about it only after a whole run burned down.
     */
    private Classification classify(UUID traceId, String idempotencyKey, String requestText) {
        CodingModelTurnService turns = modelTurns.getIfAvailable();
        if (turns == null) {
            throw failure("CODING_CLASSIFY_UNAVAILABLE",
                    "요청을 접수할 AI 통로가 꺼져 있습니다. 시스템 담당자에게 알려 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        UUID turnId = UUID.nameUUIDFromBytes(
                ("coding-intake-classify:" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        CodingModelTurnContract.Response response;
        try {
            response = turns.executeNaturalCms(new CodingModelTurnContract.Request(
                    CodingModelTurnContract.SCHEMA_VERSION,
                    turnId,
                    // No Job exists yet; the turn ledger keeps this row under the same
                    // deterministic identifier, and nothing joins turn rows to Jobs.
                    turnId,
                    traceId,
                    digest("classify-key", idempotencyKey, requestText),
                    1,
                    1,
                    "intake_classify",
                    "coding-intake-classify-v1",
                    digest("classify", requestText, ""),
                    List.of("CHAT", "STRUCTURED_OUTPUT"),
                    List.of(
                            objectMapper.createObjectNode()
                                    .put("role", "system").put("content", CLASSIFY_PROMPT),
                            objectMapper.createObjectNode()
                                    .put("role", "user").put("content", requestText)),
                    List.of(),
                    ProviderResponseFormat.jsonSchema(classifySchema()).requestContract(),
                    Instant.now(clock).plus(CLASSIFY_DEADLINE)));
        }
        catch (ProviderGatewayException gatewayFailure) {
            throw failure("CODING_CLASSIFY_UNAVAILABLE",
                    "요청 내용을 읽는 데 실패했습니다. 잠시 후 다시 시도해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!(response.responseFormat()
                instanceof CodingModelTurnContract.JsonSchemaResponseFormat structured)) {
            throw failure("CODING_CLASSIFY_UNAVAILABLE",
                    "요청 내용을 읽는 데 실패했습니다. 잠시 후 다시 시도해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        JsonNode verdict = structured.structuredOutput();
        String target = verdict.path("target").asText("");
        String firstText = verdict.path("firstText").asText("").strip();
        String secondText = verdict.path("secondText").asText("").strip();
        return switch (target) {
            case "server" -> new Classification(CodingRepositories.BACKEND, null);
            case "screen" -> new Classification(CodingRepositories.FRONTEND, null);
            case "both" -> {
                if (firstText.isEmpty() || secondText.isEmpty()) {
                    // The schema requires the fields but cannot require them to say anything.
                    // Without both sentences there is nothing truthful to show or to run.
                    throw failure("CODING_CLASSIFY_UNAVAILABLE",
                            "요청 내용을 읽는 데 실패했습니다. 잠시 후 다시 시도해 주세요.",
                            HttpStatus.SERVICE_UNAVAILABLE);
                }
                yield new Classification(CodingRepositories.BACKEND,
                        new CodingConsoleContract.SplitPlan(firstText, secondText));
            }
            default -> throw failure("CODING_CLASSIFY_UNAVAILABLE",
                    "요청 내용을 읽는 데 실패했습니다. 잠시 후 다시 시도해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        };
    }

    private JsonNode classifySchema() {
        ObjectNode schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("target").add("firstText").add("secondText");
        ObjectNode properties = schema.putObject("properties");
        // No enum: the schema validator admits only type/properties/required/additionalProperties.
        // The three allowed words live in the prompt, and an answer outside them is refused by
        // the switch that reads the verdict.
        properties.putObject("target").put("type", "string");
        properties.putObject("firstText").put("type", "string");
        properties.putObject("secondText").put("type", "string");
        return schema;
    }

    private ProfileVersionRepository.AdminStoredProfileVersion activeProfile() {
        return profileVersions.findAll(PROFILE_KEY).stream()
                .filter(version -> "ACTIVE".equals(version.status()))
                .findFirst()
                .orElseThrow(() -> failure(
                        "CODING_PROFILE_NOT_ACTIVE",
                        "활성화된 AI 설정이 없습니다. 먼저 AI 설정을 활성화해 주세요.",
                        HttpStatus.CONFLICT));
    }

    /** The runner's file list, or nothing when an older runner did not send one. */
    private static List<String> paths(JsonNode files) {
        if (files == null || !files.isArray()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode file : files) {
            if (file.isTextual() && !file.asText().isBlank()) {
                paths.add(file.asText());
            }
        }
        return List.copyOf(paths);
    }

    private static List<String> nodeIds(JsonNode snapshot) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : snapshot.path("nodes")) {
            JsonNode id = node.path("id");
            if (id.isTextual() && !id.asText().isBlank()) {
                ids.add(id.asText());
            }
        }
        return List.copyOf(ids);
    }

    /** What the scan reports: the commit a Job is pinned to, and the files it may be shown. */
    private record ScanResult(String baseSha, List<String> files) { }

    /**
     * Asks the runner for {@code origin/dev} and waits. The runner resolves a ref it already
     * has rather than fetching, so the wait is its poll interval and not a network round trip.
     * The same answer carries the repository's tracked files, so the agents can be handed the
     * files they may change instead of searching for them.
     */
    private ScanResult scan(String repository) {
        UUID taskId = runner.enqueue(
                SCAN_KIND, objectMapper.createObjectNode().put("repo", repository));

        for (int poll = 0; poll < maxShaPolls; poll++) {
            CodingRunnerService.TaskOutcome outcome = runner.taskOutcome(taskId, SCAN_KIND);
            if ("FAILED".equals(outcome.status())) {
                throw failure("CODING_BASE_SHA_UNAVAILABLE",
                        "실행기가 현재 코드 기준을 읽지 못했습니다: "
                                + (outcome.errorCode() == null ? "원인 미상" : outcome.errorCode()),
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
            JsonNode result = outcome.result();
            if (result != null) {
                return new ScanResult(prefixed(result.path("sha")), paths(result.path("files")));
            }
            sleep(shaPollInterval);
        }
        // Nothing claimed the task. The runner lives outside Docker and a person has to start
        // it, so say that rather than leaving a Job stuck in the queue forever.
        throw failure("CODING_RUNNER_NOT_RESPONDING",
                "실행기가 응답하지 않습니다. 실행기가 켜져 있는지 확인해 주세요.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * The runner answers {@code sha = 'unchanged'} when someone has edited the scan folder: it
     * keeps the evidence rather than overwriting it. That is a word, not a commit, and the
     * runner would fail to check it out later.
     */
    private static String prefixed(JsonNode sha) {
        String value = sha.isTextual() ? sha.asText().strip() : "";
        if (!BARE_SHA1.matcher(value).matches()) {
            throw failure("CODING_BASE_SHA_UNAVAILABLE",
                    "실행기가 현재 코드 기준을 알려주지 못했습니다. 스캔 폴더 상태를 확인해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return "sha1:" + value;
    }

    private void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("CODING_BASE_SHA_UNAVAILABLE",
                    "요청 준비가 중단되었습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * The contract wants a sha256 in these two fields but nothing verifies them against a real
     * context or policy document. Deriving them keeps two Jobs of the same request comparable
     * instead of scattering random values through the audit trail.
     */
    private static String digest(String label, String first, String second) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hashed = sha256.digest(
                    (label + ' ' + first + ' ' + second).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hashed) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return "sha256:" + hex;
        }
        catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is required.", absent);
        }
    }

    private static CodingJobLifecycleException failure(
            String code, String message, HttpStatus status) {
        return new CodingJobLifecycleException(code, message, status);
    }
}
