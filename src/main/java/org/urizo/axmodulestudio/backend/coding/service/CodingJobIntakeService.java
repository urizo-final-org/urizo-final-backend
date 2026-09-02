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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
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
    private static final String PROFILE_KEY = "LLM_OPS";
    private static final String GRAPH_STEP = "start";
    private static final String PROMPT_VERSION = "coding-plan-v1";
    private static final Duration JOB_LIFETIME = Duration.ofHours(1);
    private static final int MAX_REQUEST_CHARACTERS = 10_000;

    /**
     * All three, including STRUCTURED_OUTPUT. A Job that omits it is accepted and then refused
     * at the first model turn, which reads as an unrelated failure much later.
     */
    private static final List<String> CAPABILITIES =
            List.of("CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT");

    /**
     * Placeholders. Nothing joins on them today and no project or repository row exists to
     * point at; they are stable so two Jobs of the same repository group together if a real
     * registry ever arrives.
     */
    private static final UUID PROJECT_ID =
            UUID.fromString("bc3a6b45-d6a8-4bf1-8932-bcd6989de304");
    private static final UUID REPOSITORY_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** The runner reports a bare 40-character sha; the Job contract wants it prefixed. */
    private static final Pattern BARE_SHA1 = Pattern.compile("^[0-9a-f]{40}$");

    /**
     * Counted in polls rather than measured against the clock. A deadline computed from the
     * injected Clock never arrives when that Clock is fixed, which is exactly how a test
     * supplies one; the loop then spins forever. Sixty half-second polls is the same thirty
     * seconds and cannot depend on time appearing to pass.
     */
    private final int maxShaPolls;
    private final Duration shaPollInterval;

    private final CodingHandlerCommandService commands;
    private final CodingRunnerService runner;
    private final ProfileVersionRepository profileVersions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingJobIntakeService(
            CodingHandlerCommandService commands,
            CodingRunnerService runner,
            ProfileVersionRepository profileVersions,
            ObjectMapper objectMapper,
            Clock clock) {
        this(commands, runner, profileVersions, objectMapper, clock,
                60, Duration.ofMillis(500));
    }

    CodingJobIntakeService(
            CodingHandlerCommandService commands,
            CodingRunnerService runner,
            ProfileVersionRepository profileVersions,
            ObjectMapper objectMapper,
            Clock clock,
            int maxShaPolls,
            Duration shaPollInterval) {
        this.commands = Objects.requireNonNull(commands, "commands are required");
        this.runner = Objects.requireNonNull(runner, "runner is required");
        this.profileVersions = Objects.requireNonNull(
                profileVersions, "profileVersions are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.maxShaPolls = maxShaPolls;
        this.shaPollInterval = shaPollInterval;
    }

    public CodingHandlerContract.CreateCodingJobResponse create(
            AuthenticatedActor actor,
            UUID traceId,
            String idempotencyKey,
            CodingConsoleContract.CreateJobRequest body) {
        String repository = body == null ? null : body.repository();
        String requestText = body == null || body.requestText() == null
                ? null : body.requestText().strip();

        if (!CodingConsoleService.REPOSITORY.equals(repository)) {
            // runner.ps1 knows how to check out and preview the frontend but its TEST command
            // for it is unimplemented, so a frontend Job stops before the preview a human is
            // supposed to approve. Refusing here is kinder than failing three stages in.
            throw failure("CODING_REPOSITORY_NOT_SUPPORTED",
                    "지금은 backend 저장소만 요청할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        if (requestText == null || requestText.isEmpty()) {
            throw failure("CODING_REQUEST_TEXT_REQUIRED",
                    "요청 내용을 입력해 주세요.", HttpStatus.BAD_REQUEST);
        }
        if (requestText.length() > MAX_REQUEST_CHARACTERS) {
            throw failure("CODING_REQUEST_TEXT_TOO_LONG",
                    "요청 내용이 너무 깁니다.", HttpStatus.BAD_REQUEST);
        }

        ProfileVersionRepository.AdminStoredProfileVersion profile = activeProfile();
        List<String> nodes = nodeIds(profile.snapshot());
        if (!nodes.contains(GRAPH_STEP)) {
            // The Job authority refuses a graphStep outside allowedNodes, so this would be
            // rejected on the very first stage with a message about scope rather than shape.
            throw failure("CODING_PROFILE_START_NODE_MISSING",
                    "활성 AI 설정에 시작 노드가 없습니다.", HttpStatus.CONFLICT);
        }

        String baseSha = currentDevSha(repository);
        Instant now = Instant.now(clock);

        CodingHandlerContract.CreateCodingJobRequest request =
                new CodingHandlerContract.CreateCodingJobRequest(
                        "1.0",
                        profile.profileVersionId(),
                        PROJECT_ID,
                        REPOSITORY_ID,
                        GRAPH_STEP,
                        baseSha,
                        digest("context", requestText, baseSha),
                        digest("policy", profile.profileVersionId().toString(), baseSha),
                        PROMPT_VERSION,
                        CAPABILITIES,
                        nodes,
                        now.plus(JOB_LIFETIME),
                        requestText);
        return commands.create(actor, traceId, idempotencyKey, request);
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

    /**
     * Asks the runner for {@code origin/dev} and waits. The runner resolves a ref it already
     * has rather than fetching, so the wait is its poll interval and not a network round trip.
     */
    private String currentDevSha(String repository) {
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
                return prefixed(result.path("sha"));
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
