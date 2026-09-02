package org.urizo.axmodulestudio.backend.coding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailScanContract;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailSelectionContract;

/**
 * Stores which scanned folders the Coding model is allowed into.
 *
 * <p>A save replaces the whole choice for one repository, so removing a folder from the screen
 * turns it off rather than leaving a row nobody can see. A path with no row is off.
 *
 * <p>A fixed Denylist path is refused here as well as hidden from the scan. The list lives in code
 * precisely so that no stored row can grant access to it, and a row that named one would be a
 * quiet contradiction between what is stored and what is enforced.
 */
@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class GuardrailPathSelectionService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    GuardrailPathSelectionService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.transactions = Objects.requireNonNull(transactions, "transactions are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public GuardrailSelectionContract.SelectionList selections(String repository) {
        requireKnownRepository(repository);
        List<GuardrailSelectionContract.Selection> stored = jdbc.query(
                "SELECT path, enabled, label FROM app.guardrail_path_selection "
                        + "WHERE repository = ? ORDER BY path",
                (row, index) -> new GuardrailSelectionContract.Selection(
                        row.getString("path"),
                        row.getBoolean("enabled"),
                        row.getString("label")),
                repository);
        return new GuardrailSelectionContract.SelectionList(repository, stored);
    }

    public GuardrailSelectionContract.SelectionList save(
            GuardrailSelectionContract.SaveRequest request) {
        String repository = request.repository();
        requireKnownRepository(repository);
        List<GuardrailSelectionContract.Selection> selections = validated(request.selections());
        transactions.executeWithoutResult(status -> {
            // A path the administrator no longer lists must stop being stored, otherwise a folder
            // that is deleted and later recreated would come back already allowed.
            List<String> paths = selections.stream()
                    .map(GuardrailSelectionContract.Selection::path).toList();
            if (paths.isEmpty()) {
                jdbc.update("DELETE FROM app.guardrail_path_selection WHERE repository = ?",
                        repository);
            }
            else {
                jdbc.update("DELETE FROM app.guardrail_path_selection "
                                + "WHERE repository = ? AND NOT (path = ANY (?))",
                        repository, paths.toArray(String[]::new));
            }
            for (GuardrailSelectionContract.Selection selection : selections) {
                jdbc.update("INSERT INTO app.guardrail_path_selection ("
                                + "guardrail_path_selection_id, repository, path, enabled, label) "
                                + "VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT (repository, path) DO UPDATE SET "
                                + "enabled = EXCLUDED.enabled, label = EXCLUDED.label, "
                                + "updated_at = CURRENT_TIMESTAMP",
                        UUID.randomUUID(), repository, selection.path(),
                        selection.enabled(), selection.label());
            }
        });
        return selections(repository);
    }

    /**
     * The guardrail the job was created under. Empty when the job predates the snapshot, which the
     * caller must tell apart from a job that was deliberately given nothing.
     */
    public List<String> jobSnapshot(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId is required");
        List<String> stored = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(snapshot_json -> 'allowedPaths') "
                        + "FROM app.guardrail_job_snapshot WHERE job_id = ?",
                String.class, jobId);
        return List.copyOf(stored);
    }

    /**
     * Rejects the whole save when any entry is unusable, rather than storing the part that passed.
     * A half-applied guardrail is worse than a refused one: nobody would know which half.
     */
    private static List<GuardrailSelectionContract.Selection> validated(
            List<GuardrailSelectionContract.Selection> selections) {
        Set<String> seen = new LinkedHashSet<>();
        List<GuardrailSelectionContract.Selection> validated = new ArrayList<>();
        for (GuardrailSelectionContract.Selection selection : selections) {
            String path = selection.path().trim();
            if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                    || path.contains("\\") || path.contains("..")) {
                throw badRequest(
                        "GUARDRAIL_PATH_INVALID",
                        "A guardrail path must be repository-relative and /-separated.");
            }
            if (GuardrailPathPolicy.isDenied(path)) {
                throw badRequest(
                        "GUARDRAIL_PATH_DENIED",
                        "A fixed guardrail path cannot be selected: " + path);
            }
            if (!seen.add(path)) {
                throw badRequest(
                        "GUARDRAIL_PATH_DUPLICATED",
                        "A guardrail path is listed more than once: " + path);
            }
            String label = selection.label() == null || selection.label().isBlank()
                    ? null : selection.label().trim();
            validated.add(new GuardrailSelectionContract.Selection(
                    path, selection.enabled(), label));
        }
        return List.copyOf(validated);
    }

    private static void requireKnownRepository(String repository) {
        if (!GuardrailScanContract.REPOSITORIES.contains(repository)) {
            throw badRequest(
                    "GUARDRAIL_REPOSITORY_NOT_REGISTERED",
                    "The repository is not registered for a guardrail scan.");
        }
    }

    private static CodingWorkerException badRequest(String code, String message) {
        return new CodingWorkerException(code, message, HttpStatus.BAD_REQUEST);
    }
}
