package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CodingHandlerMigrationTest {

    @Test
    void exposesSnapshotSelectedApprovalNodesAndRoundsWithoutAStageMap()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V20260831181151833__expose_snapshot_approval_authority.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DROP CONSTRAINT ck_coding_approval_decision_node")
                .contains("DROP CONSTRAINT ck_coding_approval_decision_round")
                .contains("node_id ~ '^[a-z][a-z0-9_-]{0,63}$'")
                .contains("stage_round >= 1")
                .doesNotContain("stage = 'SCOPE' AND node_id")
                .doesNotContain("stage = 'GITHUB' AND node_id")
                .doesNotContain("stage_round BETWEEN 1 AND 3");
    }

    @Test
    void keepsFeatureStateInDedicatedTablesAndEnforcesRetryAndRegistryBounds()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V20260830111238338__create_coding_handler_results.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE app.coding_job_request")
                .contains("CREATE TABLE app.coding_pipeline_attempt")
                .contains("CREATE TABLE app.coding_handler_result")
                .contains("CREATE TABLE app.coding_approval_decision")
                .contains("WHERE status = 'ACTIVE'")
                .contains("pipeline_attempt < 3")
                .contains("next_pipeline_attempt = pipeline_attempt + 1")
                .contains("handler_key = 'coding.deploy_request'")
                .contains("stage IN ('CANDIDATE', 'GITHUB', 'CMS', 'DEPLOY')")
                .doesNotContain("result_type = 'CHECK'")
                .doesNotContain("ALTER TABLE app.coding_runner_task")
                .doesNotContain("ALTER TABLE app.coding_job\n")
                .doesNotContain("ALTER TABLE app.ai_profile_version")
                .doesNotContain("ALTER TABLE app.coding_approval ");
    }
}
