package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AiJobMonitoringMigrationTest {

    @Test
    void createsOnlyThePayloadFreeMonitoringReadModelWithExactOccurrenceIdentity()
            throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260907084859068__create_ai_job_monitoring_read_model.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.ai_job_monitoring_state")
                .contains("CREATE TABLE app.ai_job_node_occurrence")
                .contains("UNIQUE (job_id, pipeline_attempt, execution_attempt, node_sequence)")
                .contains("monitor_revision BIGINT NOT NULL DEFAULT 1")
                .contains("monitor_status IN ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED')")
                .contains("GRANT SELECT, INSERT, UPDATE ON app.ai_job_monitoring_state TO ai_workspace")
                .contains("GRANT SELECT, INSERT, UPDATE ON app.ai_job_node_occurrence TO ai_workspace")
                .doesNotContain(
                        "prompt", "completion", "source", "diff", "tool_input",
                        "tool_output", "ALTER TABLE app.coding_job",
                        "ALTER TABLE app.natural_cms_job");
    }
}
