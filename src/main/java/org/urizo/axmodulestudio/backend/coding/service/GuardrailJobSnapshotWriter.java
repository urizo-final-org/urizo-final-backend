package org.urizo.axmodulestudio.backend.coding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Copies the current guardrail choice as the one a job is judged by.
 *
 * <p>A job can run for a long time. Judging it by whatever the selection says when it finishes
 * would let a setting change mid-run silently rewrite the rules the job already worked under.
 *
 * <p>This deliberately uses the Job lifecycle connection rather than the worker one. The copy has
 * to be written in the same transaction that creates the job, so that a job can never exist
 * without the guardrail it is measured against. The two connections are different accounts and
 * therefore different transactions.
 */
@Service
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class GuardrailJobSnapshotWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    GuardrailJobSnapshotWriter(
            @Qualifier("codingJobLifecycleJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    /**
     * Writes the snapshot and returns the allowed paths it recorded.
     *
     * <p>Only enabled paths are copied. A row stored as off and a path never stored mean the same
     * thing, and keeping both shapes would invite them to be treated differently later.
     */
    public List<String> capture(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId is required");
        List<String> allowed = jdbc.queryForList(
                "SELECT repository || ':' || path FROM app.guardrail_path_selection "
                        + "WHERE enabled ORDER BY repository, path",
                String.class);
        ObjectNode snapshot = objectMapper.createObjectNode();
        ArrayNode allowedPaths = snapshot.putArray("allowedPaths");
        allowed.forEach(allowedPaths::add);
        snapshot.set("rules", rules());
        // The job request is replayable, so a repeated initialize must not rewrite the copy.
        jdbc.update("INSERT INTO app.guardrail_job_snapshot (job_id, snapshot_json) "
                        + "VALUES (?, ?::jsonb) ON CONFLICT (job_id) DO NOTHING",
                jobId, snapshot.toString());
        return List.copyOf(allowed);
    }

    /**
     * The path-independent rules, copied for the same reason the paths are.
     *
     * <p>Read through the Job lifecycle connection so that the whole copy is written in one
     * transaction with the job. Reading the rules on a second connection would leave a window in
     * which the paths came from before an administrator's edit and the rules from after it.
     */
    private ObjectNode rules() {
        List<ObjectNode> stored = jdbc.query(
                "SELECT allow_new_dependency, max_changed_files, max_changed_lines "
                        + "FROM app.guardrail_rule",
                (row, index) -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("allowNewDependency", row.getBoolean("allow_new_dependency"));
                    // Written as null rather than omitted, so a reader cannot mistake an unset
                    // limit for a snapshot that was taken before limits existed.
                    node.put("maxChangedFiles", (Integer) row.getObject("max_changed_files"));
                    node.put("maxChangedLines", (Integer) row.getObject("max_changed_lines"));
                    return node;
                });
        if (stored.isEmpty()) {
            throw new IllegalStateException("The guardrail rule row is missing.");
        }
        return stored.get(0);
    }
}
