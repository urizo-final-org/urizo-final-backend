package org.urizo.axmodulestudio.backend.knowledge.controller;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
        List<CandidateDocument> documents = jdbc.query(
                "SELECT external_document_id, title, content, category, content_digest "
                        + "FROM app.source_document WHERE knowledge_version_id = ? "
                        + "AND title IS NOT NULL AND title <> '' ORDER BY external_document_id",
                (rs, row) -> new CandidateDocument(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                versionId);
        int stride = Math.max(1, documents.size() / EvaluationQuestionPlanner.SAMPLE_DOCUMENTS);
        List<CandidateDocument> sampled = new java.util.ArrayList<>();
        for (int index = 0; index < documents.size()
                && sampled.size() < EvaluationQuestionPlanner.SAMPLE_DOCUMENTS; index += stride) {
            sampled.add(documents.get(index));
        }
        return sampled;
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
