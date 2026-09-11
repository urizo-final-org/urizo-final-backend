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

    /**
     * 필드 선택 표는 울타리가 관리하는 넷으로 닫힌다.
     *
     * <p>TEMPLATE이 들어오면 저장 한 번에 템플릿이 통째로 닫힌다. 코드의 관리 대상 집합과
     * 이 CHECK가 같은 넷이어야 두 겹으로 막힌다.
     */
    @Test
    void fieldSelectionAllowsOnlyTheFourGuardedResources() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260911052249613__create_natural_cms_field_selection.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.natural_cms_field_selection")
                .contains("resource_type IN ('MENU', 'BOARD', 'BOARD_POST', 'CONTENT')")
                .contains("UNIQUE (resource_type, field_name)")
                .doesNotContain("TEMPLATE");
    }

    /** 판정하는 연결이 판정 기준을 고칠 수 없어야 울타리가 울타리로 남는다. */
    @Test
    void fieldSelectionLetsTheJudgingConnectionReadButNotWrite() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260911052249613__create_natural_cms_field_selection.sql"));

        assertThat(migration)
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE "
                        + "ON app.natural_cms_field_selection TO ai_workspace;")
                .contains("GRANT SELECT ON app.natural_cms_field_selection TO cms_app;")
                .doesNotContain("UPDATE ON app.natural_cms_field_selection TO cms_app");
    }

    /**
     * 규칙 행은 사라지거나 늘어날 수 없다.
     *
     * <p>비어 있을 수 있는 설정 표는 "규칙 없음"과 "표가 깨짐"을 같은 상태로 만든다.
     * 기본키가 상수라 두 번째 행이 들어가지 않고, INSERT·DELETE를 아무에게도 주지 않는다.
     */
    @Test
    void ruleKeepsExactlyOneRowThatNobodyCanInsertOrDelete() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260911052301375__create_natural_cms_rule.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.natural_cms_rule")
                .contains("natural_cms_rule_id BOOLEAN PRIMARY KEY DEFAULT TRUE")
                .contains("CONSTRAINT ck_natural_cms_rule_single_row CHECK (natural_cms_rule_id)")
                .contains("INSERT INTO app.natural_cms_rule (natural_cms_rule_id) VALUES (TRUE);")
                .contains("GRANT SELECT, UPDATE ON app.natural_cms_rule TO ai_workspace;")
                .contains("GRANT SELECT ON app.natural_cms_rule TO cms_app;")
                .doesNotContain("INSERT ON app.natural_cms_rule TO")
                .doesNotContain("DELETE ON app.natural_cms_rule TO");
    }

    /** 저장 전후를 가르는 값이 있어야 설치 직후 기능이 멎지 않는다. */
    @Test
    void ruleCarriesTheConfiguredFlagThatSeparatesDefaultsFromChoices() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260911052301375__create_natural_cms_rule.sql"));

        assertThat(migration)
                .contains("allow_delete BOOLEAN NOT NULL DEFAULT TRUE")
                .contains("configured BOOLEAN NOT NULL DEFAULT FALSE");
    }
}
