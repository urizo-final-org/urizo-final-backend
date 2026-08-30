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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    private static final String READ_FILE_SCHEMA_DIGEST =
            "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194";
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
                String content = execute(parsed, toolBinding);
                byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
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
                                + "'SUCCEEDED', 'text/plain', ?, ?, ?, ?, ?)",
                        executionId, parsed.requestId(), parsed.toolCallId(), parsed.jobId(),
                        parsed.traceId(), parsed.leaseId(), parsed.idempotencyKey(), requestDigest,
                        parsed.expectedStateVersion(), parsed.toolName(), parsed.requestedPaths().get(0),
                        parsed.arguments().toString(), objectMapper.valueToTree(parsed.requestedPaths()).toString(),
                        toolBinding.workspaceId(), toolBinding.expectedHead(),
                        contentBytes.length, resultDigest,
                        content, Timestamp.from(now), Timestamp.from(now));
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
        requireObjectFields(tool.path("arguments"), Set.of("path"), "tool arguments");
        if (!READ_FILE_SCHEMA_DIGEST.equals(text(request, "argumentSchemaDigest"))) {
            throw validation("Tool argument schema digest is invalid.");
        }
        JsonNode requestedPaths = request.path("requestedPaths");
        if (!requestedPaths.isArray() || requestedPaths.size() != 1
                || !"README.md".equals(requestedPaths.get(0).asText())
                || !"read_file".equals(text(tool, "name"))
                || !"README.md".equals(text(tool.path("arguments"), "path"))) {
            throw new CodingToolException(
                    "PATH_POLICY_DENIED",
                    "The local Tool Gateway permits read_file for README.md only.",
                    HttpStatus.FORBIDDEN);
        }
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
                text(request, "jobState"), text(tool, "name"), tool.path("arguments").deepCopy(),
                strings(requestedPaths));
    }

    private String execute(ParsedRequest request, ToolBinding binding) {
        McpPlatformClient client = mcpClients.getIfAvailable();
        if (client == null) {
            return fixtureContent;
        }
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("workspace", binding.workspaceId().toString());
        arguments.put("expectedHead", gitHash(binding.expectedHead()));
        arguments.put("path", text(request.arguments(), "path"));
        JsonNode result;
        try {
            result = client.callTool(request.toolName(), arguments);
        }
        catch (McpPlatformException failure) {
            throw unavailable();
        }
        if (result.path("isError").asBoolean()
                || !result.path("structuredContent").path("content").isTextual()
                || !request.requestedPaths().get(0).equals(
                        result.path("structuredContent").path("path").asText())) {
            throw new CodingToolException(
                    "TOOL_EXECUTION_FAILED",
                    "The MCP coding tool returned an unsuccessful result.",
                    HttpStatus.BAD_GATEWAY);
        }
        return result.path("structuredContent").path("content").asText();
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
        return rows.get(0);
    }

    private JobAuthority requireAuthority(UUID jobId) {
        List<JobAuthority> rows = jdbc.query(
                "SELECT trace_id, status, state_version, worker_lease_id, worker_lease_expires_at, "
                        + "actor_id, project_id, repository_id, graph_step, base_sha, context_digest, "
                        + "policy_hash, allowed_capabilities, allowed_nodes, expires_at "
                        + "FROM app.coding_job WHERE job_id = ? "
                        + "AND authority_source = 'SPRING_CONTROL_PLANE' FOR SHARE",
                (rs, row) -> new JobAuthority(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getObject(4, UUID.class),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                        rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12),
                        Set.of((String[]) rs.getArray(13).getArray()),
                        Set.of((String[]) rs.getArray(14).getArray()),
                        rs.getTimestamp(15).toInstant()), jobId);
        if (rows.isEmpty()) {
            throw new CodingToolException(
                    "JOB_NOT_FOUND", "Authoritative coding job not found.",
                    HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
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

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            throw validation("requestedPaths must be an array.");
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw validation("requestedPaths must contain non-empty strings.");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
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
            String contextDigest, String policyHash, Set<String> allowedCapabilities,
            Set<String> allowedNodes, Instant expiresAt) { }
    private record ToolBinding(UUID workspaceId, String expectedHead, String expectedDiffDigest) { }
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
