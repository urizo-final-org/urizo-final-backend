package org.urizo.axmodulestudio.backend.coding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailRuleContract;

/**
 * The stored guardrail rules that do not name a path, and the copy a single job is judged by.
 *
 * <p>The stored row is the administrator's current intent. The copy inside the job snapshot is what
 * a running job is measured against, so changing the setting halfway through a job cannot rewrite
 * the rules that job already worked under.
 */
@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class GuardrailRuleService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    GuardrailRuleService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public GuardrailRuleContract.Rules rules() {
        List<GuardrailRuleContract.Rules> stored = jdbc.query(
                "SELECT allow_new_dependency, max_changed_files, max_changed_lines "
                        + "FROM app.guardrail_rule",
                (row, index) -> new GuardrailRuleContract.Rules(
                        row.getBoolean("allow_new_dependency"),
                        (Integer) row.getObject("max_changed_files"),
                        (Integer) row.getObject("max_changed_lines")));
        // The migration seeds the row and nobody holds INSERT or DELETE on the table, so an absent
        // row means the schema is not what this code was built against rather than "no rules yet".
        if (stored.isEmpty()) {
            throw new CodingWorkerException(
                    "GUARDRAIL_RULE_STORE_UNAVAILABLE",
                    "The guardrail rule row is missing.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return stored.get(0);
    }

    /** Updates the one row in place. There is no row to create, so this is never an insert. */
    public GuardrailRuleContract.Rules save(GuardrailRuleContract.Rules request) {
        Objects.requireNonNull(request, "request is required");
        requirePositiveOrUnset(request.maxChangedFiles(), "maxChangedFiles");
        requirePositiveOrUnset(request.maxChangedLines(), "maxChangedLines");
        int updated = jdbc.update(
                "UPDATE app.guardrail_rule SET allow_new_dependency = ?, max_changed_files = ?, "
                        + "max_changed_lines = ?, updated_at = CURRENT_TIMESTAMP",
                request.allowNewDependency(),
                request.maxChangedFiles(),
                request.maxChangedLines());
        if (updated == 0) {
            throw new CodingWorkerException(
                    "GUARDRAIL_RULE_STORE_UNAVAILABLE",
                    "The guardrail rule row is missing.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return rules();
    }

    /**
     * The rules the job was created under.
     *
     * <p>Empty when the job predates the snapshot. The caller then applies no size rule at all,
     * the same way an empty path selection applies none, because a job created before the rule
     * existed was never judged against it.
     */
    public Optional<GuardrailRuleContract.Rules> jobRules(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId is required");
        List<String> stored = jdbc.queryForList(
                "SELECT snapshot_json ->> 'rules' FROM app.guardrail_job_snapshot WHERE job_id = ?",
                String.class, jobId);
        if (stored.isEmpty() || stored.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(readRules(stored.get(0)));
    }

    private GuardrailRuleContract.Rules readRules(String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new CodingWorkerException(
                    "GUARDRAIL_RULE_SNAPSHOT_INVALID",
                    "The guardrail rules copied for this job cannot be read.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return new GuardrailRuleContract.Rules(
                node.path("allowNewDependency").asBoolean(false),
                limit(node.path("maxChangedFiles")),
                limit(node.path("maxChangedLines")));
    }

    /**
     * A missing or null entry is no limit. A stored number is trusted as written rather than
     * clamped, because silently repairing a snapshot would change the rules the job ran under.
     */
    private static Integer limit(JsonNode node) {
        return node.isIntegralNumber() ? node.intValue() : null;
    }

    private static void requirePositiveOrUnset(Integer limit, String field) {
        if (limit != null && limit <= 0) {
            throw new CodingWorkerException(
                    "GUARDRAIL_RULE_LIMIT_INVALID",
                    "A guardrail limit must be left unset or be greater than zero: " + field,
                    HttpStatus.BAD_REQUEST);
        }
    }
}
