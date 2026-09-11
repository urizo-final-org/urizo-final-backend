package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CodingHandlerMigrationTest {

    @Test
    void extendsOnlyThePrMergeDeployContractsAndRunnerAllowlist() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V20260903065222608__extend_pr_deploy_handler_contracts.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("'coding.pr_complete'")
                .contains("'coding.dev_merge_check'")
                .contains("'coding.deploy'")
                .contains("'DEV_MERGE'")
                .contains("'DEPLOYMENT'")
                .contains("'CHECK_DEV_MERGE'")
                .contains("'DEPLOY_LOCAL_COMPOSE'")
                .contains("payload ->> 'mergeSha'")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("DROP TABLE");
    }

    /* AI04-021 opens the deployment request and merge check rows to the frontend as well; the
     * repository set is the same one the pull request row already accepts, nothing else moves. */
    @Test
    void allowsBothPublishedRepositoriesThroughTheDeploymentRows() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V20260910025913767__allow_frontend_deployment_handlers.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DROP CONSTRAINT ck_coding_handler_result_ai04_016_payload")
                .contains("ADD CONSTRAINT ck_coding_handler_result_ai04_021_payload")
                .contains("handler_key <> 'coding.dev_merge_check'")
                .contains("handler_key <> 'coding.deploy_request'")
                .doesNotContain("payload ->> 'repository' = 'backend'")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("DROP TABLE");
        assertThat(sql.split("payload ->> 'repository' IN \\('backend', 'frontend'\\)", -1))
                .as("pr_complete, dev_merge_check and deploy_request each name both repositories")
                .hasSize(4);
    }

    @Test
    void allowsFrontendOnlyForPrCompletionAndKeepsDeploymentBackendOnly()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V20260908161528042__allow_system_pr_repositories.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DROP CONSTRAINT ck_coding_handler_result_ai04_009_payload")
                .contains("ADD CONSTRAINT ck_coding_handler_result_ai04_016_payload")
                .contains("payload ->> 'repository' IN ('backend', 'frontend')")
                .contains("handler_key <> 'coding.dev_merge_check'")
                .contains("handler_key <> 'coding.deploy_request'")
                .contains("payload ->> 'repository' = 'backend'")
                .doesNotContain("payload ->> 'validationHash'")
                .doesNotContain("payload ->> 'authorLogin'")
                .doesNotContain("payload -> 'reused'")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("DROP TABLE");
    }

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
