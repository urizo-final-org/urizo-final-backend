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
     * 동작 선택 표는 가드레일이 관리하는 넷으로 닫힌다.
     *
     * <p>TEMPLATE이 들어오면 저장 한 번에 템플릿이 통째로 닫힌다. 코드의 관리 대상 집합과
     * 이 CHECK가 같은 넷이어야 두 겹으로 막힌다.
     */
    @Test
    void operationSelectionAllowsOnlyTheFourGuardedResources() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260912090830717__create_natural_cms_operation_selection.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.natural_cms_operation_selection")
                .contains("resource_type IN ('MENU', 'BOARD', 'BOARD_POST', 'CONTENT')")
                .contains("operation IN ('CREATE', 'UPDATE', 'DELETE')")
                .contains("UNIQUE (resource_type, operation)")
                // 아래 DROP 문에만 TEMPLATE 이 없는지 보는 것이 아니라, CHECK 어디에도 없어야 한다.
                .doesNotContain("'TEMPLATE'");
    }

    /** 판정하는 연결이 판정 기준을 고칠 수 없어야 가드레일이 가드레일로 남는다. */
    @Test
    void operationSelectionLetsTheJudgingConnectionReadButNotWrite() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260912090830717__create_natural_cms_operation_selection.sql"));

        assertThat(migration)
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE "
                        + "ON app.natural_cms_operation_selection TO ai_workspace;")
                .contains("GRANT SELECT ON app.natural_cms_operation_selection TO cms_app;")
                .doesNotContain("UPDATE ON app.natural_cms_operation_selection TO cms_app");
    }

    /**
     * 저장한 적 없는 설치에는 한 행도 넣지 않는다.
     *
     * <p>넣으면 "아직 정하지 않음"이 "이렇게 정함"으로 바뀌어, 설치 직후 상태가 관리자가
     * 저장한 것처럼 읽힌다. 그때부터 코드가 새로 여는 동작이 조용히 닫힌 채로 남는다.
     */
    @Test
    void operationSelectionMigratesOnlyWhenTheAdministratorHadSavedBefore() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260912090830717__create_natural_cms_operation_selection.sql"));

        assertThat(migration)
                .contains("INSERT INTO app.natural_cms_operation_selection")
                .contains("WHERE rule.configured")
                // 등록·수정은 끌 수단이 없었으므로 켜진 상태로 옮긴다.
                .contains("CASE WHEN op.name = 'DELETE' THEN rule.allow_delete ELSE TRUE END");
    }

    /**
     * Job 응답에 필드를 더하면 파이프라인이 통째로 멎는다.
     *
     * <p>Orchestrator가 이 응답을 허용 목록으로 검사한다
     * ({@code natural_cms_domain_client.NaturalCmsJob.from_dict}의 {@code allowed}).
     * 목록에 없는 키가 하나라도 있으면 {@code WORKER_RESPONSE_INVALID}로 Job 전체를 거부하고,
     * 새 요청은 전부 {@code ACTIVE}로 멈춘 채 화면은 「미리보기를 받지 못했습니다」만 띄운다.
     * 실제로 겪었다 — 화면에만 필요한 값을 여기 실었다가 로컬 파이프라인이 멎었다.
     *
     * <p>화면에만 필요한 값은 {@code RefusalResponse}처럼 별도 경로로 낸다. 이 목록을 늘려야
     * 하면 Orchestrator의 {@code allowed}를 같은 PR에서 함께 고쳐야 하고, 그것은 다른
     * 저장소라 6번·팀장과의 협의가 먼저다.
     */
    @Test
    void jobResponseKeepsTheShapeTheOrchestratorAccepts() {
        assertThat(NaturalCmsContract.JobResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "schemaVersion", "jobId", "traceId", "profileVersionId",
                        "pipelineAttempt", "stateVersion", "status", "requestText",
                        "resource", "structuredCommand", "previewId", "previewHash",
                        "previewValid", "approvalDecision", "approvalFeedback",
                        "createdAt", "updatedAt",
                        // AI05-020 이 더했다. Orchestrator 의 allowed 에도 같은 작업에서 함께
                        // 넣었다 — 늘려야 할 때는 그렇게 두 저장소를 같이 고쳐야 한다.
                        "preview");
    }

    /** 쓰이지 않게 된 필드 선택 표는 같은 리비전에서 지운다. 앞선 파일은 체크섬 때문에 못 고친다. */
    @Test
    void operationSelectionDropsTheFieldSelectionItReplaces() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260912090830717__create_natural_cms_operation_selection.sql"));

        assertThat(migration).contains("DROP TABLE app.natural_cms_field_selection;");
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
