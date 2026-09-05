package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NaturalCmsMigrationTest {

    @Test
    void migrationCreatesOnlyTheCmsJobAndResultBoundary() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260830162029912__create_natural_cms_result_boundary.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.natural_cms_job")
                .contains("CREATE TABLE app.natural_cms_handler_result")
                .contains("preview_id UUID")
                .contains("structured_command JSONB")
                .doesNotContain("workspace_id")
                .doesNotContain("candidate_sha")
                .doesNotContain("diff_digest")
                .doesNotContain("pull_request");
    }

    @Test
    void approvedApplyUsesTheProductTransactionWithoutGivingAiDirectCmsWrite()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260831165912245__unify_natural_cms_apply_transaction.sql"));

        assertThat(migration)
                .contains("'NATURAL_CMS_JOB'")
                .contains("GRANT SELECT ON app.natural_cms_job TO cms_app")
                .contains("GRANT UPDATE (status, preview_valid, updated_at)")
                .contains("GRANT SELECT, INSERT ON app.natural_cms_handler_result TO cms_app")
                .doesNotContain("cms_content TO ai_workspace");
    }

    @Test
    void resourceTypeMigrationAllowsExactlyTheFourNaturalCmsResources()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260903055920035__allow_natural_cms_resource_types.sql"));

        assertThat(migration)
                .contains("ALTER TABLE app.natural_cms_job")
                .contains("DROP CONSTRAINT ck_natural_cms_job_resource")
                .contains("ADD CONSTRAINT ck_natural_cms_job_resource CHECK")
                .contains("ALTER TABLE app.natural_cms_handler_result")
                .contains("DROP CONSTRAINT ck_natural_cms_result_resource")
                .contains("ADD CONSTRAINT ck_natural_cms_result_resource CHECK")
                .doesNotContain("CMS_COMPOSITE")
                .doesNotContain("resource_type = 'CONTENT'");

        assertThat(migration.lines()
                .filter(line -> line.contains(
                        "resource_type IN ('MENU', 'BOARD', 'CONTENT', 'TEMPLATE')"))
                .count())
                .isEqualTo(2);
    }

    @Test
    void resourceTypeMigrationPreservesTheExistingResourceIdPattern()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260903055920035__allow_natural_cms_resource_types.sql"));

        assertThat(migration.lines()
                .filter(line -> line.contains(
                        "resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'"))
                .count())
                .isEqualTo(2);
    }

    @Test
    void outboxConflictMigrationGrantsOnlyTheRequiredEventKeyRead()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260903065547140__grant_natural_cms_outbox_conflict_read.sql"));

        assertThat(migration)
                .contains("GRANT SELECT (event_key) ON app.transactional_outbox TO ai_workspace;")
                .doesNotContain("GRANT SELECT ON app.transactional_outbox TO ai_workspace;");
    }
}
