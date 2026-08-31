package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingToolContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformClient;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformException;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingToolService {

    static final Map<String, String> CODING_TOOL_SCHEMA_DIGESTS = Map.of(
            "read_file", "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194",
            "search_code", "sha256:4ef58a30900281deda5141481d8ec042c002273f1aac8f7851a6020b8f4d1fd5",
            "read_diff", "sha256:99334726611ccf58a148b0814696bfa6fe08c1b2d027e946beccf5a74331c9aa",
            "apply_patch", "sha256:f6594e18aaedfb029106fa669c557027854ec5f86cce436fcec1723791743cd7",
            "run_check", "sha256:9c8ff63f21a3414335f7f7788d00bdfb096480b37a1cee5e9084d2954439824a",
            "check_package_allowlist", "sha256:99334726611ccf58a148b0814696bfa6fe08c1b2d027e946beccf5a74331c9aa",
            "scan_changed_files", "sha256:99334726611ccf58a148b0814696bfa6fe08c1b2d027e946beccf5a74331c9aa");
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "messageType", "requestId", "toolCallId", "jobId",
            "traceId", "leaseId", "idempotencyKey", "attempt", "graphStep",
            "attemptScope", "expectedStateVersion", "deadlineAt", "actor", "jobState",
            "repository", "contextDigest", "policyHash", "argumentSchemaDigest",
            "requestedPaths", "tool", "approval");
    private static final Set<String> ACTOR_FIELDS = Set.of("actorId", "projectId", "role");
    private static final Set<String> REPOSITORY_FIELDS = Set.of(
            "repositoryId", "baseSha", "candidateSha");
    private static final Set<String> APPROVAL_FIELDS = Set.of(
            "approvalId", "scopeDigest", "expiresAt");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String fixtureContent;
    private final ObjectProvider<McpPlatformClient> mcpClients;

    CodingToolService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<McpPlatformClient> mcpClients) throws IOException {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.mcpClients = mcpClients;
        this.fixtureContent = new ClassPathResource("coding-fixture/README.md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public CodingToolContract.Accepted submit(String authorization, JsonNode request) {
        ParsedRequest parsed = parse(request);
        byte[] credentialDigest = credentialDigest(authorization);
        byte[] requestDigest = sha256Bytes(canonical(request));
        try {
            CodingToolContract.Accepted response = transactions.execute(status -> {
                authenticate(credentialDigest);
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        resultSet -> { },
                        "TOOL:" + parsed.jobId() + ":" + parsed.idempotencyKey());
                List<ExistingExecution> existing = jdbc.query(
                        "SELECT execution_id, request_id, tool_call_id, trace_id, request_digest, created_at "
                                + "FROM app.coding_tool_execution WHERE job_id = ? AND idempotency_key = ?",
                        (rs, row) -> new ExistingExecution(
                                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                                rs.getBytes(5), rs.getTimestamp(6).toInstant()),
                        parsed.jobId(), parsed.idempotencyKey());
                if (!existing.isEmpty()) {
                    ExistingExecution replay = existing.get(0);
                    if (!MessageDigest.isEqual(requestDigest, replay.requestDigest())) {
                        throw conflict("IDEMPOTENCY_KEY_REUSED",
                                "Tool idempotency key was reused with another request.");
                    }
                    return accepted(replay.executionId(), replay.requestId(), replay.toolCallId(),
                            parsed.jobId(), replay.traceId(), parsed.idempotencyKey(), replay.createdAt());
                }
                JobAuthority authority = requireAuthority(parsed.jobId());
                ToolBinding toolBinding = mcpClients.getIfAvailable() == null
                        ? new ToolBinding(null, authority.baseSha(), null)
                        : toolBinding(parsed.jobId(), authority.baseSha());
                validateAuthority(parsed, authority, toolBinding);
                UUID executionId = UUID.randomUUID();
                Instant now = Instant.now(clock);
                ToolOutput output = execute(parsed, toolBinding);
                byte[] contentBytes = output.content().getBytes(StandardCharsets.UTF_8);
                if (contentBytes.length > 200_000) {
                    Arrays.fill(contentBytes, (byte) 0);
                    throw new CodingToolException(
                            "TOOL_EXECUTION_FAILED",
                            "The MCP coding tool result exceeds the approved result bound.",
                            HttpStatus.BAD_GATEWAY);
                }
                String resultDigest = "sha256:" + java.util.HexFormat.of()
                        .formatHex(sha256Bytes(contentBytes));
                jdbc.update("INSERT INTO app.coding_tool_execution "
                                + "(execution_id, request_id, tool_call_id, job_id, trace_id, lease_id, "
                                + "idempotency_key, request_digest, expected_state_version, tool_name, "
                                + "requested_path, arguments_json, requested_paths, workspace_id, "
                                + "candidate_sha, status, result_media_type, result_size_bytes, "
                                + "result_digest, result_content, created_at, completed_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, "
                                + "'SUCCEEDED', ?, ?, ?, ?, ?, ?)",
                        executionId, parsed.requestId(), parsed.toolCallId(), parsed.jobId(),
                        parsed.traceId(), parsed.leaseId(), parsed.idempotencyKey(), requestDigest,
                        parsed.expectedStateVersion(), parsed.toolName(), parsed.requestedPaths().get(0),
                        parsed.arguments().toString(), objectMapper.valueToTree(parsed.requestedPaths()).toString(),
                        toolBinding.workspaceId(), toolBinding.expectedHead(), output.mediaType(),
                        contentBytes.length, resultDigest,
                        output.content(), Timestamp.from(now), Timestamp.from(now));
                Arrays.fill(contentBytes, (byte) 0);
                return accepted(executionId, parsed.requestId(), parsed.toolCallId(), parsed.jobId(),
                        parsed.traceId(), parsed.idempotencyKey(), now);
            });
            if (response == null) {
                throw unavailable();
            }
            return response;
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
            Arrays.fill(requestDigest, (byte) 0);
        }
    }

    public CodingToolContract.Succeeded execution(String authorization, UUID executionId) {
        byte[] digest = credentialDigest(authorization);
        try {
            return transactions.execute(status -> {
                authenticate(digest);
                return requireExecution(executionId).succeeded();
            });
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public CodingToolContract.ResultContent result(String authorization, UUID executionId) {
        byte[] digest = credentialDigest(authorization);
        try {
            return transactions.execute(status -> {
                authenticate(digest);
                ExecutionRow row = requireExecution(executionId);
                return new CodingToolContract.ResultContent(
                        version(), row.requestId(), row.toolCallId(), row.jobId(), row.traceId(),
                        row.idempotencyKey(), row.executionId(), row.mediaType(), row.sizeBytes(),
                        row.digest(), row.content());
            });
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    StageAuthority stageAuthority(
            String authorization, UUID jobId, int expectedStateVersion) {
        byte[] digest = credentialDigest(authorization);
        try {
            StageAuthority authority = transactions.execute(status -> {
                authenticate(digest);
                JobAuthority job = requireAuthority(jobId);
                Instant now = Instant.now(clock);
                if (!"RUNNING".equals(job.status())
                        || job.stateVersion() != expectedStateVersion
                        || job.leaseId() == null
                        || job.leaseExpiresAt() == null
                        || !job.leaseExpiresAt().isAfter(now)
                        || !job.expiresAt().isAfter(now)
                        || !"central.default".equals(job.guardrailProfileKey())) {
                    throw new CodingToolException(
                            "JOB_STATE_CONFLICT",
                            "The Coding Job is not authorized for stage execution.",
                            HttpStatus.CONFLICT);
                }
                return new StageAuthority(
                        job.traceId(), job.stateVersion(), job.leaseId(), job.actorId(),
                        job.projectId(), job.repositoryId(), job.graphStep(), job.baseSha(),
                        job.contextDigest(), job.policyHash(), job.promptVersion(),
                        job.allowedCapabilities(), job.allowedNodes(), job.profileAllowedTools(),
                        job.expiresAt(), job.profileVersionId());
            });
            if (authority == null) {
                throw unavailable();
            }
            return authority;
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private ParsedRequest parse(JsonNode request) {
        requireObjectFields(request, TOP_LEVEL_FIELDS, "tool request");
        if (!"1.0".equals(text(request, "schemaVersion"))
                || !"TOOL_REQUEST".equals(text(request, "messageType"))) {
            throw validation("Tool request version or messageType is invalid.");
        }
        JsonNode actor = request.path("actor");
        JsonNode repository = request.path("repository");
        JsonNode approval = request.path("approval");
        JsonNode tool = request.path("tool");
        requireObjectFields(actor, ACTOR_FIELDS, "actor");
        requireObjectFields(repository, REPOSITORY_FIELDS, "repository");
        requireObjectFields(approval, APPROVAL_FIELDS, "approval");
        requireObjectFields(tool, Set.of("name", "arguments"), "tool");
        String toolName = text(tool, "name");
        JsonNode arguments = tool.path("arguments");
        validateToolArguments(toolName, arguments);
        if (!Objects.equals(
                CODING_TOOL_SCHEMA_DIGESTS.get(toolName),
                text(request, "argumentSchemaDigest"))) {
            throw validation("Tool argument schema digest is invalid.");
        }
        List<String> requestedPaths = requestedPaths(request.path("requestedPaths"));
        validateRequestedPaths(toolName, arguments, requestedPaths);
        int expectedVersion = integer(request, "expectedStateVersion");
        if (expectedVersion < 1 || integer(request, "attempt") < 1) {
            throw validation("Tool request attempt or stateVersion is invalid.");
        }
        return new ParsedRequest(
                uuid(request, "requestId"), uuid(request, "toolCallId"),
                uuid(request, "jobId"), uuid(request, "traceId"), uuid(request, "leaseId"),
                text(request, "idempotencyKey"), expectedVersion, text(request, "graphStep"),
                Instant.parse(text(request, "deadlineAt")),
                uuid(actor, "actorId"), uuid(actor, "projectId"), text(actor, "role"),
                uuid(repository, "repositoryId"), text(repository, "baseSha"),
                text(repository, "candidateSha"), text(request, "contextDigest"),
                text(request, "policyHash"), uuid(approval, "approvalId"),
                text(approval, "scopeDigest"), Instant.parse(text(approval, "expiresAt")),
                text(request, "jobState"), toolName, arguments.deepCopy(), requestedPaths);
    }

    private ToolOutput execute(ParsedRequest request, ToolBinding binding) {
        McpPlatformClient client = mcpClients.getIfAvailable();
        if (client == null) {
            if (!"read_file".equals(request.toolName())) {
                throw unavailable();
            }
            return new ToolOutput("text/plain", fixtureContent);
        }
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("workspace", binding.workspaceId().toString());
        arguments.put("expectedHead", gitHash(binding.expectedHead()));
        switch (request.toolName()) {
            case "read_file" -> arguments.put("path", text(request.arguments(), "path"));
            case "search_code" -> {
                arguments.put("query", text(request.arguments(), "query"));
                JsonNode roots = request.arguments().path("roots");
                if (roots.isArray() && !roots.isEmpty()) {
                    arguments.put("scope", roots.get(0).asText());
                }
            }
            case "read_diff" -> { }
            case "apply_patch" -> {
                requireDiffDigest(binding);
                arguments.put("expectedDiffDigest", binding.expectedDiffDigest());
                arguments.put("patch", text(request.arguments(), "patch"));
            }
            case "run_check" -> {
                requireDiffDigest(binding);
                arguments.put("expectedDiffDigest", binding.expectedDiffDigest());
                arguments.put("profile", text(request.arguments(), "checkId"));
            }
            case "check_package_allowlist", "scan_changed_files" -> {
                requireDiffDigest(binding);
                arguments.put("expectedDiffDigest", binding.expectedDiffDigest());
            }
            default -> throw validation("Tool name is not registered.");
        }
        JsonNode result;
        try {
            result = client.callTool(request.toolName(), arguments);
        }
        catch (McpPlatformException failure) {
            throw unavailable();
        }
        JsonNode structured = result.path("structuredContent");
        if (result.path("isError").asBoolean() || !structured.isObject()) {
            // The refusal reason is a correction instruction - the stage replays it to
            // the model - so it survives here instead of being flattened to one phrase.
            throw new CodingToolException(
                    "TOOL_EXECUTION_FAILED",
                    "The MCP coding tool refused the call. " + mcpRefusalReason(result),
                    HttpStatus.BAD_GATEWAY);
        }
        validateMcpResult(request, structured);
        if ("read_file".equals(request.toolName())) {
            return new ToolOutput("text/plain", structured.path("content").asText());
        }
        try {
            return new ToolOutput("application/json", objectMapper.writeValueAsString(structured));
        }
        catch (IOException failure) {
            throw unavailable();
        }
    }

    private ToolBinding toolBinding(UUID jobId, String baseSha) {
        List<ToolBinding> rows = jdbc.query("""
                SELECT cpa.workspace_id,
                       COALESCE((
                           SELECT chr.candidate_sha
                           FROM app.coding_handler_result chr
                           WHERE chr.job_id = cpa.job_id
                             AND chr.pipeline_attempt = cpa.pipeline_attempt
                             AND chr.candidate_sha IS NOT NULL
                           ORDER BY chr.recorded_at DESC, chr.result_id DESC
                           LIMIT 1), ?) AS expected_head,
                       (SELECT chr.diff_digest
                        FROM app.coding_handler_result chr
                        WHERE chr.job_id = cpa.job_id
                          AND chr.pipeline_attempt = cpa.pipeline_attempt
                          AND chr.diff_digest IS NOT NULL
                        ORDER BY chr.recorded_at DESC, chr.result_id DESC
                        LIMIT 1) AS expected_diff_digest
                FROM app.coding_pipeline_attempt cpa
                WHERE cpa.job_id = ? AND cpa.status = 'ACTIVE'
                ORDER BY cpa.pipeline_attempt DESC
                LIMIT 1
                FOR SHARE
                """, (rs, row) -> new ToolBinding(
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("expected_head"),
                        rs.getString("expected_diff_digest")),
                baseSha, jobId);
        if (rows.size() != 1 || rows.get(0).workspaceId() == null) {
            throw new CodingToolException(
                    "TOOL_NOT_ALLOWED",
                    "The Coding Job does not have an authoritative MCP workspace binding.",
                    HttpStatus.FORBIDDEN);
        }
        ToolBinding binding = rows.get(0);
        if (binding.expectedDiffDigest() != null) {
            return binding;
        }
        List<String> toolResults = jdbc.query("""
                SELECT result_content
                FROM app.coding_tool_execution
                WHERE job_id = ? AND status = 'SUCCEEDED'
                  AND tool_name IN ('read_diff', 'apply_patch', 'run_check',
                                    'check_package_allowlist', 'scan_changed_files')
                ORDER BY completed_at DESC, execution_id DESC
                LIMIT 10
                """, (rs, row) -> rs.getString(1), jobId);
        for (String content : toolResults) {
            try {
                JsonNode value = objectMapper.readTree(content);
                String digest = value.hasNonNull("diffDigest")
                        ? value.path("diffDigest").asText()
                        : value.path("digest").asText();
                if (SHA256_DIGEST.matcher(digest).matches()) {
                    return new ToolBinding(
                            binding.workspaceId(), binding.expectedHead(), digest);
                }
            }
            catch (JsonProcessingException ignored) {
                // Older read_file rows contain plain text and are not diff bindings.
            }
        }
        return binding;
    }

    private JobAuthority requireAuthority(UUID jobId) {
        List<JobAuthority> rows = jdbc.query(
                "SELECT job.trace_id, job.status, job.state_version, job.worker_lease_id, "
                        + "job.worker_lease_expires_at, job.actor_id, job.project_id, "
                        + "job.repository_id, job.graph_step, job.base_sha, job.context_digest, "
                        + "job.policy_hash, job.prompt_version, job.allowed_capabilities, "
                        + "job.allowed_nodes, job.expires_at, "
                        + "profile.snapshot_json::text AS profile_snapshot, "
                        + "job.profile_version_id "
                        + "FROM app.coding_job job "
                        + "JOIN app.ai_profile_version profile "
                        + "ON profile.profile_version_id = job.profile_version_id "
                        + "WHERE job.job_id = ? AND job.authority_source = 'SPRING_CONTROL_PLANE' "
                        + "AND profile.profile_key = 'LLM_OPS' "
                        + "AND profile.status IN ('ACTIVE', 'INACTIVE') FOR SHARE",
                (rs, row) -> {
                    RuntimePolicy policy = decodeRuntimePolicy(
                            objectMapper, rs.getString(17));
                    return new JobAuthority(
                            rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                            rs.getObject(4, UUID.class),
                            rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                            rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                            rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10),
                            rs.getString(11), rs.getString(12), rs.getString(13),
                            Set.of((String[]) rs.getArray(14).getArray()),
                            Set.of((String[]) rs.getArray(15).getArray()),
                            policy.allowedTools(), policy.guardrailProfileKey(),
                            rs.getTimestamp(16).toInstant(),
                            rs.getObject(18, UUID.class));
                }, jobId);
        if (rows.isEmpty()) {
            throw new CodingToolException(
                    "JOB_NOT_FOUND", "Authoritative coding job not found.",
                    HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    static RuntimePolicy decodeRuntimePolicy(ObjectMapper objectMapper, String encoded) {
        try {
            JsonNode snapshot = objectMapper.readTree(encoded);
            JsonNode allowed = snapshot.path("toolPolicy").path("allowedTools");
            JsonNode guardrail = snapshot.path("guardrailProfileKey");
            if (!snapshot.isObject() || !allowed.isArray()
                    || !guardrail.isTextual()
                    || !"central.default".equals(guardrail.textValue())) {
                throw invalidRuntimePolicy();
            }
            Set<String> tools = new HashSet<>();
            for (JsonNode tool : allowed) {
                if (!tool.isTextual()
                        || !CODING_TOOL_SCHEMA_DIGESTS.containsKey(tool.textValue())
                        || !tools.add(tool.textValue())) {
                    throw invalidRuntimePolicy();
                }
            }
            return new RuntimePolicy(tools, guardrail.textValue());
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalidRuntimePolicy();
        }
    }

    private void validateAuthority(
            ParsedRequest request, JobAuthority job, ToolBinding toolBinding) {
        Instant now = Instant.now(clock);
        UUID expectedApproval = UUID.nameUUIDFromBytes(
                (request.jobId() + ":approval").getBytes(StandardCharsets.UTF_8));
        boolean matches = job.traceId().equals(request.traceId())
                && "RUNNING".equals(job.status())
                && "RUNNING".equals(request.jobState())
                && job.stateVersion() == request.expectedStateVersion()
                && Objects.equals(job.leaseId(), request.leaseId())
                && job.leaseExpiresAt().isAfter(now)
                && job.actorId().equals(request.actorId())
                && job.projectId().equals(request.projectId())
                && job.repositoryId().equals(request.repositoryId())
                && job.graphStep().equals(request.graphStep())
                && job.allowedNodes().contains(request.graphStep())
                && job.allowedCapabilities().contains("TOOL_CALLING")
                && job.profileAllowedTools().contains(request.toolName())
                && "central.default".equals(job.guardrailProfileKey())
                && job.baseSha().equals(request.baseSha())
                && toolBinding.expectedHead().equals(request.candidateSha())
                && job.contextDigest().equals(request.contextDigest())
                && job.policyHash().equals(request.policyHash())
                && "DEVELOPER".equals(request.role())
                && expectedApproval.equals(request.approvalId())
                && job.policyHash().equals(request.approvalScopeDigest())
                && request.deadlineAt().isAfter(now)
                && !request.deadlineAt().isAfter(job.expiresAt())
                && request.approvalExpiresAt().isAfter(now)
                && !request.approvalExpiresAt().isAfter(job.expiresAt());
        if (!matches) {
            throw new CodingToolException(
                    "TOOL_NOT_ALLOWED",
                    "Spring rejected the tool candidate against authoritative job policy.",
                    HttpStatus.FORBIDDEN);
        }
    }

    private ExecutionRow requireExecution(UUID executionId) {
        List<ExecutionRow> rows = jdbc.query(
                "SELECT te.execution_id, te.request_id, te.tool_call_id, te.job_id, te.trace_id, "
                        + "te.idempotency_key, te.result_media_type, te.result_size_bytes, "
                        + "te.result_digest, te.result_content, te.completed_at, cj.base_sha "
                        + "FROM app.coding_tool_execution te JOIN app.coding_job cj ON cj.job_id = te.job_id "
                        + "WHERE te.execution_id = ? AND te.status = 'SUCCEEDED'",
                (rs, row) -> new ExecutionRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
                        rs.getInt(8), rs.getString(9), rs.getString(10),
                        rs.getTimestamp(11).toInstant(), rs.getString(12)), executionId);
        if (rows.isEmpty()) {
            throw new CodingToolException(
                    "TOOL_EXECUTION_NOT_FOUND", "Tool execution not found.", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private void authenticate(byte[] credentialDigest) {
        Instant now = Instant.now(clock);
        List<UUID> matches = jdbc.query(
                "SELECT credential_id FROM app.coding_service_credential "
                        + "WHERE credential_digest = ? AND status IN ('ACTIVE', 'RETIRING') "
                        + "AND valid_from <= ? AND (valid_until IS NULL OR valid_until > ?) FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class),
                credentialDigest, Timestamp.from(now), Timestamp.from(now));
        if (matches.size() != 1) {
            throw new CodingToolException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbc.update("UPDATE app.coding_service_credential SET last_used_at = ? WHERE credential_id = ?",
                Timestamp.from(now), matches.get(0));
    }

    private static CodingToolContract.Accepted accepted(
            UUID executionId, UUID requestId, UUID toolCallId, UUID jobId,
            UUID traceId, String key, Instant acceptedAt) {
        return new CodingToolContract.Accepted(
                version(), "TOOL_ACCEPTED", requestId, toolCallId, jobId, traceId, key,
                executionId, "ACCEPTED", "/internal/coding/tool-executions/" + executionId,
                10, acceptedAt);
    }

    private byte[] canonical(JsonNode node) {
        try { return objectMapper.writeValueAsBytes(node); }
        catch (IOException failure) { throw validation("Tool request cannot be encoded."); }
    }

    private static byte[] credentialDigest(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new CodingToolException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] token = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        try { return sha256Bytes(token); }
        finally { Arrays.fill(token, (byte) 0); }
    }

    private static byte[] sha256Bytes(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static void requireObjectFields(JsonNode node, Set<String> expected, String label) {
        if (!node.isObject()) { throw validation(label + " must be an object."); }
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(actual::add);
        if (!actual.equals(expected)) { throw validation(label + " fields are invalid."); }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw validation(field + " must be a non-empty string.");
        }
        return value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        try { return UUID.fromString(text(node, field)); }
        catch (IllegalArgumentException failure) { throw validation(field + " must be a UUID."); }
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) { throw validation(field + " must be an integer."); }
        return value.intValue();
    }

    private static List<String> requestedPaths(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > 50) {
            throw validation("requestedPaths must be a bounded non-empty array.");
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw validation("requestedPaths must contain relative paths.");
            }
            values.add(relativePath(value.asText(), "requestedPaths"));
        }
        if (values.size() != Set.copyOf(values).size()) {
            throw validation("requestedPaths contains duplicates.");
        }
        return List.copyOf(values);
    }

    private static void validateToolArguments(String toolName, JsonNode arguments) {
        switch (toolName) {
            case "read_file" -> {
                requireObjectFields(arguments, Set.of("path"), "tool arguments");
                relativePath(text(arguments, "path"), "path");
            }
            case "search_code" -> {
                Set<String> actual = objectFields(arguments, "tool arguments");
                if (!actual.contains("query")
                        || !Set.of("query", "roots", "maxResults").containsAll(actual)
                        || text(arguments, "query").length() > 1_000) {
                    throw validation("search_code arguments are invalid.");
                }
                if (arguments.has("roots")) {
                    requestedPaths(arguments.path("roots"));
                }
                if (arguments.has("maxResults")) {
                    int maximum = integer(arguments, "maxResults");
                    if (maximum < 1 || maximum > 500) {
                        throw validation("search_code maxResults is invalid.");
                    }
                }
            }
            case "read_diff", "check_package_allowlist", "scan_changed_files" ->
                    requireObjectFields(arguments, Set.of(), "tool arguments");
            case "apply_patch" -> {
                requireObjectFields(arguments, Set.of("patch"), "tool arguments");
                String patch = text(arguments, "patch");
                // The MCP workspace only applies a patch whose first line is a canonical
                // 'diff --git a/PATH b/PATH' header, so the precheck demands the same
                // start. The old '--- ' start could never pass the MCP policy.
                if (patch.length() > 50_000 || !patch.startsWith("diff --git a/")) {
                    throw validation("apply_patch patch is invalid: it must start with a "
                            + "'diff --git a/PATH b/PATH' line.");
                }
            }
            case "run_check" -> {
                requireObjectFields(arguments, Set.of("checkId"), "tool arguments");
                if (!Set.of("git-diff-check", "python-syntax")
                        .contains(text(arguments, "checkId"))) {
                    throw new CodingToolException(
                            "CHECK_PROFILE_NOT_ALLOWED",
                            "The requested check profile is not registered.",
                            HttpStatus.FORBIDDEN);
                }
            }
            default -> throw validation("Tool name is not registered.");
        }
    }

    private static void validateRequestedPaths(
            String toolName, JsonNode arguments, List<String> requestedPaths) {
        if ("read_file".equals(toolName)
                && !requestedPaths.equals(List.of(text(arguments, "path")))) {
            throw pathDenied();
        }
        if ("search_code".equals(toolName)) {
            if (arguments.has("roots")
                    && (arguments.path("roots").size() != 1
                    || !requestedPaths.equals(requestedPaths(arguments.path("roots"))))) {
                throw pathDenied();
            }
            if (!arguments.has("roots") && !requestedPaths.equals(List.of("."))) {
                throw pathDenied();
            }
        }
        if (!Set.of("read_file", "search_code").contains(toolName)
                && !requestedPaths.equals(List.of("."))) {
            throw pathDenied();
        }
    }

    private static void validateMcpResult(ParsedRequest request, JsonNode result) {
        switch (request.toolName()) {
            case "read_file" -> {
                if (!result.path("content").isTextual()
                        || !request.requestedPaths().get(0).equals(result.path("path").asText())) {
                    throw failedResult();
                }
            }
            case "search_code" -> {
                if (!result.path("query").isTextual() || !result.path("matches").isArray()
                        || !result.path("truncated").isBoolean()
                        || !result.path("scope").isTextual()
                        || !result.path("scope").asText().equals(
                                request.arguments().has("roots")
                                        ? request.arguments().path("roots").get(0).asText()
                                        : ".")) {
                    throw failedResult();
                }
            }
            case "read_diff" -> requireResultDigest(result, "digest");
            case "apply_patch", "check_package_allowlist", "scan_changed_files" ->
                    requireResultDigest(result, "diffDigest");
            case "run_check" -> {
                requireResultDigest(result, "detailsDigest");
                if (!Set.of("PASSED", "FAILED").contains(result.path("status").asText())) {
                    throw failedResult();
                }
            }
            default -> throw failedResult();
        }
    }

    private static void requireResultDigest(JsonNode result, String field) {
        if (!result.path(field).isTextual()
                || !SHA256_DIGEST.matcher(result.path(field).asText()).matches()) {
            throw failedResult();
        }
    }

    private static void requireDiffDigest(ToolBinding binding) {
        if (binding.expectedDiffDigest() == null) {
            throw new CodingToolException(
                    "TOOL_RESULT_NOT_READY",
                    "read_diff must establish the current diff digest first.",
                    HttpStatus.CONFLICT);
        }
    }

    private static Set<String> objectFields(JsonNode node, String label) {
        if (!node.isObject()) {
            throw validation(label + " must be an object.");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual;
    }

    private static String relativePath(String value, String field) {
        if (value.isBlank() || value.length() > 1_000 || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || value.contains("\\")
                || value.contains(":") || value.matches(".*%[0-9A-Fa-f]{2}.*")
                || Arrays.asList(value.split("/", -1)).contains("..")
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw pathDenied();
        }
        return value;
    }

    private static CodingToolException pathDenied() {
        return new CodingToolException(
                "PATH_POLICY_DENIED",
                "The Coding tool request exceeds the approved workspace path scope.",
                HttpStatus.FORBIDDEN);
    }

    /** One bounded line of the MCP refusal, with control characters removed. */
    private static String mcpRefusalReason(JsonNode result) {
        String text = result.path("content").path(0).path("text").asText("");
        String cleaned = text.replaceAll("\\p{Cntrl}+", " ").strip();
        if (cleaned.isEmpty()) {
            return "No reason was returned.";
        }
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    private static CodingToolException failedResult() {
        return new CodingToolException(
                "TOOL_EXECUTION_FAILED",
                "The MCP coding tool returned an invalid result.",
                HttpStatus.BAD_GATEWAY);
    }

    private static String gitHash(String value) {
        int separator = value.indexOf(':');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String version() { return CodingToolContract.SCHEMA_VERSION; }
    private static CodingToolException validation(String message) {
        return new CodingToolException("TOOL_ARGUMENTS_INVALID", message, HttpStatus.BAD_REQUEST);
    }
    private static CodingToolException conflict(String code, String message) {
        return new CodingToolException(code, message, HttpStatus.CONFLICT);
    }
    private static CodingToolException unavailable() {
        return new CodingToolException(
                "TOOL_EXECUTOR_UNAVAILABLE", "Tool Gateway is unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, true, 1_000L);
    }

    private static CodingToolException invalidRuntimePolicy() {
        return new CodingToolException(
                "TOOL_NOT_ALLOWED",
                "The bound Coding Profile runtime policy is invalid.",
                HttpStatus.FORBIDDEN);
    }

    private record ExistingExecution(
            UUID executionId, UUID requestId, UUID toolCallId, UUID traceId,
            byte[] requestDigest, Instant createdAt) { }
    private record ParsedRequest(
            UUID requestId, UUID toolCallId, UUID jobId, UUID traceId, UUID leaseId,
            String idempotencyKey, int expectedStateVersion, String graphStep,
            Instant deadlineAt, UUID actorId, UUID projectId, String role,
            UUID repositoryId, String baseSha, String candidateSha,
            String contextDigest, String policyHash, UUID approvalId,
            String approvalScopeDigest, Instant approvalExpiresAt, String jobState,
            String toolName, JsonNode arguments, List<String> requestedPaths) { }
    private record JobAuthority(
            UUID traceId, String status, int stateVersion, UUID leaseId, Instant leaseExpiresAt,
            UUID actorId, UUID projectId, UUID repositoryId, String graphStep, String baseSha,
            String contextDigest, String policyHash, String promptVersion,
            Set<String> allowedCapabilities,
            Set<String> allowedNodes, Set<String> profileAllowedTools,
            String guardrailProfileKey, Instant expiresAt, UUID profileVersionId) { }
    record RuntimePolicy(Set<String> allowedTools, String guardrailProfileKey) {
        RuntimePolicy {
            allowedTools = Set.copyOf(allowedTools);
        }
    }
    record StageAuthority(
            UUID traceId, int stateVersion, UUID leaseId, UUID actorId, UUID projectId,
            UUID repositoryId, String graphStep, String baseSha, String contextDigest,
            String policyHash, String promptVersion, Set<String> allowedCapabilities,
            Set<String> allowedNodes, Set<String> profileAllowedTools, Instant expiresAt,
            UUID profileVersionId) {
        StageAuthority {
            allowedCapabilities = Set.copyOf(allowedCapabilities);
            allowedNodes = Set.copyOf(allowedNodes);
            profileAllowedTools = Set.copyOf(profileAllowedTools);
            Objects.requireNonNull(profileVersionId, "profileVersionId is required");
        }
    }
    private record ToolBinding(UUID workspaceId, String expectedHead, String expectedDiffDigest) { }
    private record ToolOutput(String mediaType, String content) { }
    private record ExecutionRow(
            UUID executionId, UUID requestId, UUID toolCallId, UUID jobId, UUID traceId,
            String idempotencyKey, String mediaType, int sizeBytes, String digest,
            String content, Instant completedAt, String candidateSha) {
        CodingToolContract.Succeeded succeeded() {
            return new CodingToolContract.Succeeded(
                    version(), "TOOL_RESULT", requestId, toolCallId, jobId, traceId,
                    idempotencyKey, executionId, "SUCCEEDED",
                    new CodingToolContract.ResultReference(
                            mediaType,
                            "/internal/coding/tool-executions/" + executionId + "/result",
                            sizeBytes,
                            digest),
                    candidateSha, completedAt);
        }
    }
}
