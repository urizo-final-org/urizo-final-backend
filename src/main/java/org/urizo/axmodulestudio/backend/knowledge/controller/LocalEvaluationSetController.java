package org.urizo.axmodulestudio.backend.knowledge.controller;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalDevRequestGuard;
import org.urizo.axmodulestudio.backend.knowledge.batch.EvaluationQuestionPlanner;
import org.urizo.axmodulestudio.backend.knowledge.batch.EvaluationQuestionPlanner.CandidateDocument;
import org.urizo.axmodulestudio.backend.knowledge.batch.EvaluationQuestionPlanner.GeneratedQuestion;

/**
 * 골든 질문 평가셋의 생성·검수·동결(AXMS-AI02-020). 로컬 시연 전용 관리 통로다.
 *
 * <p><b>순서가 계약이다</b>: 생성(DRAFT) → 사람 검수 → 확정(CONFIRMED) → 첫 골든 평가.
 * 확정 전에는 어떤 빌드도 이 세트로 채점하지 않으므로(배치는 CONFIRMED만 읽는다),
 * "점수를 먼저 보고 세트를 고치는" 흐름이 생길 수 없다. 확정 후 세트는 수정하지 않는다 —
 * 바꾸고 싶으면 그것은 새 setVersion이다.
 *
 * <p>보안은 {@link LocalDevRequestGuard}(루프백 + CSRF)다. 공통 SecurityConfig는 건드리지
 * 않는다 — 이 경로는 공개 계약이 아니라 {@code /internal/dev} 로컬 관리 통로이고,
 * provider-credentials가 이미 같은 방식으로 동작한다.
 */
@RestController
@Profile("dev & local-full")
@RequestMapping("/internal/dev/evaluation-sets")
public class LocalEvaluationSetController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LocalDevRequestGuard requestGuard;
    private final EvaluationQuestionPlanner planner;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LocalEvaluationSetController(
            @Qualifier("productJdbcTemplate") JdbcTemplate jdbc,
            // coding 쪽 TransactionTemplate이 둘 더 있고 어느 것도 @Primary가 아니다.
            @Qualifier("productTransactionTemplate") TransactionTemplate transactions,
            LocalDevRequestGuard requestGuard,
            EvaluationQuestionPlanner planner,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.requestGuard = requestGuard;
        this.planner = planner;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping("/{knowledgeBaseId}")
    SetView view(@PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        requestGuard.requireRead(request);
        return read(knowledgeBaseId, requestGuard.csrfToken(request));
    }

    /**
     * 표본 추출 → LLM 출제 → 결정적 검증 → DRAFT 저장. LLM 호출은 트랜잭션 밖이다.
     * 이미 세트가 있으면 거절한다 — 재생성은 사람이 현 세트를 지우는 결정을 한 다음의 일이다.
     */
    @PostMapping("/{knowledgeBaseId}/generate")
    ResponseEntity<Object> generate(@PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        requestGuard.requireMutation(request);
        if (!planner.enabled()) {
            return conflict("EVALUATION_LLM_DISABLED",
                    "Set AXMS_EVAL_LLM=true to generate an evaluation set.");
        }
        SetView current = read(knowledgeBaseId, null);
        if (current.set() != null) {
            return conflict("EVALUATION_SET_EXISTS",
                    "The knowledge base already has an evaluation set.");
        }
        UUID sourceVersion = sourceVersion(knowledgeBaseId);
        if (sourceVersion == null) {
            return conflict("NO_DOCUMENTS",
                    "The knowledge base has no version with collected documents.");
        }
        List<CandidateDocument> candidates = sample(sourceVersion);
        List<GeneratedQuestion> questions = planner.generate(candidates);
        if (questions.size() < EvaluationQuestionPlanner.MIN_CONFIRMABLE) {
            return conflict("EVALUATION_SET_TOO_SMALL",
                    "Only " + questions.size() + " questions survived validation; at least "
                            + EvaluationQuestionPlanner.MIN_CONFIRMABLE + " are required.");
        }
        String set = encode(draft(sourceVersion, questions));
        Instant now = Instant.now(clock);
        Integer stored = transactions.execute(status -> jdbc.update(
                "UPDATE app.knowledge_base SET evaluation_question_set = ?::jsonb, updated_at = ? "
                        + "WHERE knowledge_base_id = ? AND evaluation_question_set IS NULL",
                set, Timestamp.from(now), knowledgeBaseId));
        if (stored == null || stored == 0) {
            return conflict("EVALUATION_SET_EXISTS",
                    "The knowledge base already has an evaluation set.");
        }
        return ResponseEntity.ok(read(knowledgeBaseId, null));
    }

    /**
     * 확정된 세트에 <b>증분</b>을 붙인다(AXMS-AI02-020 후속). 원천이 갈려 정답 문서가 사라지면
     * 채점 문항이 줄어드는데(실측: 50 → 17), 사라진 만큼을 <b>새로 들어온 문서</b>로 메운다.
     *
     * <p><b>출제 대상은 "아직 어떤 문항도 덮지 않은 문서"뿐이다.</b> 내용이 바뀐 문서는
     * 일부러 다시 출제하지 않는다 — 빈약한 출처로 빌드해 본문이 깎인 문서에 대고 문제를 다시
     * 내면, 그 빌드에 맞춘 쉬운 문제가 되어 <b>품질 저하를 시험지가 덮어 버린다</b>. 바뀐 문서는
     * 기존 문항을 그대로 두고 {@code modifiedCount}로 드러낸다.
     *
     * <p><b>대기 중 증분은 {@code questions}에 넣지 않는다.</b> 배치는 CONFIRMED 세트의
     * {@code questions}만 채점하므로, 검수 전 문항이 섞이면 "사람이 보기 전에는 채점하지
     * 않는다"는 계약이 깨진다. 확정 전까지는 {@code pendingIncrement}에 따로 둔다.
     */
    @PostMapping("/{knowledgeBaseId}/increment")
    ResponseEntity<Object> increment(@PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        requestGuard.requireMutation(request);
        if (!planner.enabled()) {
            return conflict("EVALUATION_LLM_DISABLED",
                    "Set AXMS_EVAL_LLM=true to generate an evaluation set.");
        }
        JsonNode set = read(knowledgeBaseId, null).set();
        if (set == null || !"CONFIRMED".equals(set.path("status").asText())) {
            return conflict("EVALUATION_SET_NOT_CONFIRMED",
                    "An increment can only extend a confirmed evaluation set.");
        }
        if (set.hasNonNull("pendingIncrement")) {
            return conflict("INCREMENT_ALREADY_PENDING",
                    "Confirm or discard the pending increment first.");
        }
        UUID sourceVersion = sourceVersion(knowledgeBaseId);
        if (sourceVersion == null) {
            return conflict("NO_DOCUMENTS",
                    "The knowledge base has no version with collected documents.");
        }

        Set<String> covered = coveredDocumentIds(set);
        List<CandidateDocument> candidates = sample(sourceVersion, covered);
        if (candidates.isEmpty()) {
            // 다이어그램의 "변경 없음" 가지다. LLM을 부르지 않고 그대로 끝낸다.
            return conflict("NO_NEW_DOCUMENTS",
                    "Every document in the current corpus is already covered by the set.");
        }
        List<GeneratedQuestion> questions = planner.generate(candidates);
        if (questions.isEmpty()) {
            return conflict("INCREMENT_EMPTY",
                    "No question survived validation for the new documents.");
        }

        ObjectNode pending = pendingIncrement(set, sourceVersion, candidates, covered, questions);
        ObjectNode next = set.deepCopy();
        next.set("pendingIncrement", pending);
        Instant now = Instant.now(clock);
        Integer stored = transactions.execute(status -> jdbc.update(
                "UPDATE app.knowledge_base SET evaluation_question_set = ?::jsonb, updated_at = ? "
                        + "WHERE knowledge_base_id = ? "
                        + "AND evaluation_question_set->>'status' = 'CONFIRMED' "
                        + "AND evaluation_question_set->'pendingIncrement' IS NULL",
                encode(next), Timestamp.from(now), knowledgeBaseId));
        if (stored == null || stored == 0) {
            return conflict("INCREMENT_ALREADY_PENDING",
                    "Confirm or discard the pending increment first.");
        }
        return ResponseEntity.ok(read(knowledgeBaseId, null));
    }

    /**
     * 대기 중 증분을 채점 대상으로 편입한다. <b>이 시점부터 시험지가 바뀐다</b> —
     * 그래서 이 게이트를 지나기 전과 후의 점수는 같은 숫자로 비교하지 않는다.
     */
    @PostMapping("/{knowledgeBaseId}/increment/confirm")
    ResponseEntity<Object> confirmIncrement(
            @PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        requestGuard.requireMutation(request);
        JsonNode set = read(knowledgeBaseId, null).set();
        if (set == null || !set.hasNonNull("pendingIncrement")) {
            return conflict("NO_PENDING_INCREMENT", "There is no pending increment to confirm.");
        }
        Instant now = Instant.now(clock);
        ObjectNode next = merged(set, now);

        Integer stored = transactions.execute(status -> jdbc.update(
                "UPDATE app.knowledge_base SET evaluation_question_set = ?::jsonb, updated_at = ? "
                        + "WHERE knowledge_base_id = ? "
                        + "AND evaluation_question_set->'pendingIncrement' IS NOT NULL",
                encode(next), Timestamp.from(now), knowledgeBaseId));
        if (stored == null || stored == 0) {
            return conflict("NO_PENDING_INCREMENT", "There is no pending increment to confirm.");
        }
        return ResponseEntity.ok(read(knowledgeBaseId, null));
    }

    /** 검수에서 탈락한 증분을 버린다. 확정된 문항은 건드리지 않는다. */
    @PostMapping("/{knowledgeBaseId}/increment/discard")
    ResponseEntity<Object> discardIncrement(
            @PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        requestGuard.requireMutation(request);
        Instant now = Instant.now(clock);
        Integer removed = transactions.execute(status -> jdbc.update(
                "UPDATE app.knowledge_base SET evaluation_question_set = "
                        + "evaluation_question_set - 'pendingIncrement', updated_at = ? "
                        + "WHERE knowledge_base_id = ? "
                        + "AND evaluation_question_set->'pendingIncrement' IS NOT NULL",
                Timestamp.from(now), knowledgeBaseId));
        if (removed == null || removed == 0) {
            return conflict("NO_PENDING_INCREMENT", "There is no pending increment to discard.");
        }
        return ResponseEntity.ok(read(knowledgeBaseId, null));
    }

    /** DRAFT → CONFIRMED. 사람 검수를 마친 세트만 동결한다. 그 외 상태는 전부 거절. */
    @PostMapping("/{knowledgeBaseId}/confirm")
    ResponseEntity<Object> confirm(@PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        requestGuard.requireMutation(request);
        Instant now = Instant.now(clock);
        Integer confirmed = transactions.execute(status -> jdbc.update(
                "UPDATE app.knowledge_base SET evaluation_question_set = "
                        + "jsonb_set(jsonb_set(evaluation_question_set, '{status}', '\"CONFIRMED\"'::jsonb), "
                        + "'{confirmedAt}', to_jsonb(?::text)), updated_at = ? "
                        + "WHERE knowledge_base_id = ? "
                        + "AND evaluation_question_set->>'status' = 'DRAFT'",
                now.toString(), Timestamp.from(now), knowledgeBaseId));
        if (confirmed == null || confirmed == 0) {
            return conflict("EVALUATION_SET_NOT_DRAFT",
                    "Only a draft evaluation set can be confirmed.");
        }
        return ResponseEntity.ok(read(knowledgeBaseId, null));
    }

    private SetView read(UUID knowledgeBaseId, String csrfToken) {
        List<SetView> rows = jdbc.query(
                "SELECT name, evaluation_question_set::text FROM app.knowledge_base "
                        + "WHERE knowledge_base_id = ?",
                (rs, row) -> new SetView(
                        csrfToken, knowledgeBaseId, rs.getString(1),
                        parse(rs.getString(2)), Instant.now(clock)),
                knowledgeBaseId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Knowledge base was not found.");
        }
        return rows.get(0);
    }

    /** 출제 원본 버전: 활성 버전이 있으면 그것, 없으면 문서가 있는 최신 버전. */
    private UUID sourceVersion(UUID knowledgeBaseId) {
        List<UUID> versions = jdbc.query(
                "SELECT kv.knowledge_version_id FROM app.knowledge_version kv "
                        + "JOIN app.knowledge_base kb ON kb.knowledge_base_id = kv.knowledge_base_id "
                        + "WHERE kv.knowledge_base_id = ? AND EXISTS (SELECT 1 FROM app.source_document sd "
                        + "WHERE sd.knowledge_version_id = kv.knowledge_version_id) "
                        + "ORDER BY (kv.knowledge_version_id = kb.active_version_id) DESC, "
                        + "kv.version_number DESC LIMIT 1",
                (rs, row) -> rs.getObject(1, UUID.class), knowledgeBaseId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /** 등록 순서에서 고르게 건너뛰며 표본을 뽑는다(평가 측정과 같은 이유·같은 방식). */
    private List<CandidateDocument> sample(UUID versionId) {
        return sample(versionId, Set.of());
    }

    /**
     * @param covered 이미 어떤 문항이 정답 문서로 쓰는 문서들. 증분은 이 바깥만 출제한다 —
     *     한 문서에 문항이 둘이면 그 문서가 점수에서 두 번 세어진다.
     */
    private List<CandidateDocument> sample(UUID versionId, Set<String> covered) {
        List<CandidateDocument> documents = jdbc.query(
                "SELECT external_document_id, title, content, category, content_digest "
                        + "FROM app.source_document WHERE knowledge_version_id = ? "
                        + "AND title IS NOT NULL AND title <> '' ORDER BY external_document_id",
                (rs, row) -> new CandidateDocument(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                versionId);
        List<CandidateDocument> pool = documents.stream()
                .filter(document -> !covered.contains(document.externalDocumentId()))
                .toList();
        int stride = Math.max(1, pool.size() / EvaluationQuestionPlanner.SAMPLE_DOCUMENTS);
        List<CandidateDocument> sampled = new java.util.ArrayList<>();
        for (int index = 0; index < pool.size()
                && sampled.size() < EvaluationQuestionPlanner.SAMPLE_DOCUMENTS; index += stride) {
            sampled.add(pool.get(index));
        }
        return sampled;
    }

    /**
     * 대기 중 증분을 확정 문항으로 옮긴 새 세트. <b>순수 변환이라 따로 떼어 둔다</b> —
     * 문항이 빠지거나 이력이 덮이면 시험지가 조용히 틀어지는데, DB 없이 확인할 수 있어야 한다.
     */
    static ObjectNode merged(JsonNode set, Instant confirmedAt) {
        JsonNode pending = set.path("pendingIncrement");
        ObjectNode next = set.deepCopy();
        ArrayNode questions = next.withArray("questions");
        for (JsonNode question : pending.path("questions")) {
            questions.add(question.deepCopy());
        }
        ObjectNode record = next.withArray("increments").addObject()
                .put("increment", pending.path("increment").asInt())
                .put("confirmedAt", confirmedAt.toString())
                .put("generatedAt", pending.path("generatedAt").asText())
                .put("sourceVersionId", pending.path("sourceVersionId").asText())
                .put("added", pending.path("questions").size());
        record.set("trigger", pending.path("trigger").deepCopy());
        next.remove("pendingIncrement");
        return next;
    }

    /** 확정 문항 + 대기 중 증분이 이미 정답 문서로 쓰는 문서 번호. */
    static Set<String> coveredDocumentIds(JsonNode set) {
        Set<String> covered = new java.util.HashSet<>();
        for (JsonNode question : set.path("questions")) {
            covered.add(question.path("expectedExternalDocumentId").asText());
        }
        for (JsonNode question : set.path("pendingIncrement").path("questions")) {
            covered.add(question.path("expectedExternalDocumentId").asText());
        }
        covered.remove("");
        return covered;
    }

    /** 이미 쓰인 가장 큰 qNN 다음 번호부터 이어 붙인다 — 문항 id는 세트 안에서 유일해야 한다. */
    static int nextQuestionNumber(JsonNode set) {
        int max = 0;
        for (JsonNode question : set.path("questions")) {
            String id = question.path("id").asText("");
            if (id.startsWith("q")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(1))); }
                catch (NumberFormatException ignored) { /* 형식이 다른 id는 번호로 세지 않는다 */ }
            }
        }
        return max + 1;
    }

    private ObjectNode pendingIncrement(
            JsonNode set, UUID sourceVersion, List<CandidateDocument> candidates,
            Set<String> covered, List<GeneratedQuestion> questions) {
        int number = set.path("increments").size() + 2;   // 최초 확정본이 1차다
        ObjectNode pending = objectMapper.createObjectNode()
                .put("increment", number)
                .put("status", "DRAFT")
                .put("generatedAt", Instant.now(clock).toString())
                .put("sourceVersionId", sourceVersion.toString());
        pending.set("generator", objectMapper.createObjectNode()
                .put("provider", planner.provider())
                .put("model", planner.model())
                .put("promptVersion", EvaluationQuestionPlanner.PROMPT_VERSION));
        // 왜 이 증분이 생겼는지를 숫자로 남긴다 — 나중에 "시험지가 왜 바뀌었나"의 근거다.
        pending.set("trigger", objectMapper.createObjectNode()
                .put("alreadyCovered", covered.size())
                .put("uncoveredSampled", candidates.size()));
        ArrayNode items = pending.putArray("questions");
        int next = nextQuestionNumber(set);
        for (GeneratedQuestion question : questions) {
            ObjectNode item = items.addObject()
                    .put("id", String.format("q%02d", next++))
                    .put("question", question.question())
                    .put("expectedExternalDocumentId", question.source().externalDocumentId())
                    .put("sourceContentDigest", question.source().contentDigest());
            if (question.source().category() != null) {
                item.put("category", question.source().category());
            }
            item.set("origin", objectMapper.createObjectNode().put("increment", number));
        }
        return pending;
    }

    private ObjectNode draft(UUID sourceVersion, List<GeneratedQuestion> questions) {
        ObjectNode set = objectMapper.createObjectNode()
                .put("status", "DRAFT")
                .put("setVersion", 1)
                .put("generatedAt", Instant.now(clock).toString())
                .put("sourceVersionId", sourceVersion.toString());
        set.set("generator", objectMapper.createObjectNode()
                .put("provider", planner.provider())
                .put("model", planner.model())
                .put("promptVersion", EvaluationQuestionPlanner.PROMPT_VERSION));
        ArrayNode items = set.putArray("questions");
        for (int index = 0; index < questions.size(); index++) {
            GeneratedQuestion question = questions.get(index);
            ObjectNode item = items.addObject()
                    .put("id", String.format("q%02d", index + 1))
                    .put("question", question.question())
                    .put("expectedExternalDocumentId", question.source().externalDocumentId())
                    .put("sourceContentDigest", question.source().contentDigest());
            if (question.source().category() != null) {
                item.put("category", question.source().category());
            }
        }
        return set;
    }

    private String encode(ObjectNode set) {
        try {
            return objectMapper.writeValueAsString(set);
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Evaluation set could not be encoded.", impossible);
        }
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored evaluation set is invalid.", failure);
        }
    }

    private static ResponseEntity<Object> conflict(String code, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new SafeError(code, message));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<SafeError> securityFailure(SecurityException failure) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new SafeError("LOCAL_CMS_ACCESS_DENIED", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<SafeError> validationFailure(IllegalArgumentException failure) {
        return ResponseEntity.badRequest()
                .body(new SafeError("LOCAL_CMS_VALIDATION_FAILED", failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<SafeError> storageFailure(DataAccessException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new SafeError("LOCAL_SECRET_STORE_UNAVAILABLE",
                        "The local product store is unavailable."));
    }

    record SetView(
            String csrfToken, UUID knowledgeBaseId, String knowledgeBaseName,
            JsonNode set, Instant checkedAt) {
    }

    record SafeError(String code, String message) {
    }
}
