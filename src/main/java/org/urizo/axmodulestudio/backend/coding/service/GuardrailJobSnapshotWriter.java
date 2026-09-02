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
     * <p>As paths, only enabled rows are copied: a row stored as off and a path never stored mean
     * the same thing to the file check, and keeping both shapes would invite them to be treated
     * differently later. The labels are a different matter. The analyst refuses a request in the
     * administrator's words, and a label on a row turned off is a recorded "not allowed" — the one
     * fact that stops the analyst from hoping the files it needs happen to live inside an allowed
     * folder. So both sides' labels are copied, while the off rows still contribute no path.
     */
    public List<String> capture(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId is required");
        List<StoredSelection> stored = jdbc.query(
                "SELECT repository, path, enabled, label FROM app.guardrail_path_selection "
                        + "ORDER BY repository, path",
                (row, index) -> new StoredSelection(
                        row.getString("repository"), row.getString("path"),
                        row.getBoolean("enabled"), row.getString("label")));
        List<String> allowed = stored.stream()
                .filter(StoredSelection::enabled)
                .map(selection -> selection.repository() + ":" + selection.path())
                .toList();
        ObjectNode snapshot = objectMapper.createObjectNode();
        ArrayNode allowedPaths = snapshot.putArray("allowedPaths");
        allowed.forEach(allowedPaths::add);
        areas(snapshot, "allowedAreas", stored, true);
        areas(snapshot, "deniedAreas", stored, false);
        snapshot.set("rules", rules());
        // The job request is replayable, so a repeated initialize must not rewrite the copy.
        jdbc.update("INSERT INTO app.guardrail_job_snapshot (job_id, snapshot_json) "
                        + "VALUES (?, ?::jsonb) ON CONFLICT (job_id) DO NOTHING",
                jobId, snapshot.toString());
        return List.copyOf(allowed);
    }

    /**
     * A file list is worth a prompt, not a repository. Beyond this the list stops being a
     * shortcut and becomes the wandering it was meant to replace.
     */
    private static final int MAX_SNAPSHOT_FILES = 300;

    /**
     * Adds the files the job may change, taken from the scan the job's baseSha came from.
     *
     * <p>Written after {@link #capture}, not inside it: the list comes from the runner, and
     * waiting on a host process inside the transaction that creates the job would hold a
     * database transaction open across a network call. That is safe here because the list is
     * a hint for the agents and never an authority — the enforcement stays {@code allowedPaths}
     * and the post-check on the files actually changed.
     *
     * <p>Filtered against the job's own snapshot rather than the current selection, so a job
     * is never handed a file outside the fence it was created under.
     */
    public List<String> recordFiles(UUID jobId, List<String> repositoryFiles) {
        Objects.requireNonNull(jobId, "jobId is required");
        if (repositoryFiles == null || repositoryFiles.isEmpty()) {
            return List.of();
        }
        List<String> allowed = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(snapshot_json -> 'allowedPaths') "
                        + "FROM app.guardrail_job_snapshot WHERE job_id = ?",
                String.class, jobId);
        List<String> folders = allowed.stream()
                .map(entry -> entry.substring(entry.indexOf(':') + 1))
                .filter(folder -> !folder.isBlank())
                .toList();
        if (folders.isEmpty()) {
            return List.of();
        }
        List<String> selected = repositoryFiles.stream()
                .filter(file -> folders.stream().anyMatch(folder -> file.startsWith(folder + "/")))
                .distinct()
                .limit(MAX_SNAPSHOT_FILES)
                .toList();
        if (selected.isEmpty()) {
            return List.of();
        }
        ArrayNode files = objectMapper.createArrayNode();
        selected.forEach(files::add);
        jdbc.update("UPDATE app.guardrail_job_snapshot "
                        + "SET snapshot_json = jsonb_set(snapshot_json, '{files}', ?::jsonb) "
                        + "WHERE job_id = ?",
                files.toString(), jobId);
        return List.copyOf(selected);
    }

    /** One stored guardrail row, as much of it as the snapshot needs. */
    record StoredSelection(String repository, String path, boolean enabled, String label) {

        /** The human name of the folder; the path is the only truthful fallback. */
        String area() {
            return label == null || label.isBlank() ? path : label;
        }
    }

    /** The same label on several rows is one area, not a list that repeats itself. */
    private static void areas(
            ObjectNode snapshot, String field, List<StoredSelection> stored, boolean enabled) {
        ArrayNode areas = snapshot.putArray(field);
        stored.stream()
                .filter(selection -> selection.enabled() == enabled)
                .map(StoredSelection::area)
                .distinct()
                .forEach(areas::add);
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
