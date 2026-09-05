package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class GuardrailJobSnapshotWriterTest {

    private static final UUID JOB = UUID.fromString("31313131-3131-4131-8131-313131313131");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GuardrailJobSnapshotWriter writer =
            new GuardrailJobSnapshotWriter(jdbc, mapper);

    /**
     * The rule row the migration seeds. Every test needs it, because the copy is taken in one
     * read of the selections and one of the rules.
     */
    private void storedRules(boolean allowNewDependency, Integer files, Integer lines) {
        ObjectNode rules = mapper.createObjectNode();
        rules.put("allowNewDependency", allowNewDependency);
        rules.put("maxChangedFiles", files);
        rules.put("maxChangedLines", lines);
        doReturn(List.of(rules)).when(jdbc)
                .query(contains("guardrail_rule"), any(RowMapper.class));
    }

    private void storedSelections(GuardrailJobSnapshotWriter.StoredSelection... selections) {
        doReturn(List.of(selections)).when(jdbc)
                .query(contains("guardrail_path_selection"), any(RowMapper.class));
    }

    private static GuardrailJobSnapshotWriter.StoredSelection row(
            String repository, String path, boolean enabled, String label) {
        return new GuardrailJobSnapshotWriter.StoredSelection(repository, path, enabled, label);
    }

    private JsonNode writtenSnapshot() throws Exception {
        ArgumentCaptor<Object> arguments = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(any(String.class), eq(JOB), arguments.capture());
        return mapper.readTree((String) arguments.getValue());
    }

    @Test
    @DisplayName("켜져 있는 경로만 저장소를 붙여 복사한다")
    void copiesOnlyTheEnabledPaths() throws Exception {
        storedRules(false, null, null);
        storedSelections(
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/cms",
                        true, "CMS 기능"),
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/monitoring",
                        false, "상태 점검"),
                row("frontend", "src/features/cms", true, "CMS 화면"));

        List<String> captured = writer.capture(JOB);

        assertThat(captured).containsExactly(
                "backend:src/main/java/org/urizo/axmodulestudio/backend/cms",
                "frontend:src/features/cms");
        JsonNode snapshot = writtenSnapshot();
        assertThat(snapshot.path("allowedPaths")).hasSize(2);
        assertThat(snapshot.path("allowedPaths").get(1).asText())
                .isEqualTo("frontend:src/features/cms");
    }

    @Test
    @DisplayName("꺼진 행은 경로가 아니라 라벨로 남는다 — 거부는 기록된 사실이어야 한다")
    void copiesBothSidesAsLabels() throws Exception {
        storedRules(false, null, null);
        storedSelections(
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/cms",
                        true, "CMS 기능"),
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/monitoring",
                        false, "상태 점검"),
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/integration",
                        false, "외부 연동"));

        writer.capture(JOB);

        JsonNode snapshot = writtenSnapshot();
        assertThat(snapshot.path("allowedAreas"))
                .extracting(JsonNode::asText).containsExactly("CMS 기능");
        assertThat(snapshot.path("deniedAreas"))
                .extracting(JsonNode::asText).containsExactly("상태 점검", "외부 연동");
    }

    @Test
    @DisplayName("라벨이 없는 행은 경로를 그대로 이름으로 쓴다")
    void fallsBackToThePathWhenARowHasNoLabel() throws Exception {
        storedRules(false, null, null);
        storedSelections(row("frontend", "src/features/boards", false, null));

        writer.capture(JOB);

        assertThat(writtenSnapshot().path("deniedAreas"))
                .extracting(JsonNode::asText).containsExactly("src/features/boards");
    }

    @Test
    @DisplayName("같은 라벨이 여러 행에 붙어 있어도 한 번만 적는다")
    void collapsesARepeatedLabel() throws Exception {
        storedRules(false, null, null);
        storedSelections(
                row("frontend", "src/shared/api", false, "공통 기반"),
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/common",
                        false, "공통 기반"));

        writer.capture(JOB);

        assertThat(writtenSnapshot().path("deniedAreas"))
                .extracting(JsonNode::asText).containsExactly("공통 기반");
    }

    @Test
    @DisplayName("아무것도 켜져 있지 않아도 빈 목록을 기록한다. 기록 없음과 구분되어야 한다")
    void writesAnEmptySnapshotRatherThanNothing() throws Exception {
        storedRules(false, null, null);
        storedSelections();

        assertThat(writer.capture(JOB)).isEmpty();

        JsonNode snapshot = writtenSnapshot();
        assertThat(snapshot.path("allowedPaths")).isEmpty();
        assertThat(snapshot.path("allowedAreas")).isEmpty();
        assertThat(snapshot.path("deniedAreas")).isEmpty();
    }

    @Test
    @DisplayName("같은 Job 을 다시 초기화해도 복사본을 덮어쓰지 않는다")
    void doesNotOverwriteAnExistingSnapshot() {
        storedRules(false, null, null);
        storedSelections();

        writer.capture(JOB);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(JOB), any());
        assertThat(sql.getValue()).contains("ON CONFLICT (job_id) DO NOTHING");
    }

    @Test
    @DisplayName("돌려주는 목록은 바꿀 수 없다")
    void returnsAnUnmodifiableList() {
        storedRules(false, null, null);
        storedSelections(row("backend", "src", true, null));

        List<String> captured = writer.capture(JOB);

        assertThat(captured).isUnmodifiable();
    }

    @Test
    @DisplayName("경로와 함께 부가 규칙도 같은 복사본에 담는다")
    void copiesTheRulesAlongsideThePaths() throws Exception {
        storedRules(true, 12, 400);
        storedSelections();

        writer.capture(JOB);

        JsonNode rules = writtenSnapshot().path("rules");
        assertThat(rules.path("allowNewDependency").asBoolean()).isTrue();
        assertThat(rules.path("maxChangedFiles").asInt()).isEqualTo(12);
        assertThat(rules.path("maxChangedLines").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("정하지 않은 상한은 빼지 않고 null 로 적는다. 규칙이 없던 시절과 구분되어야 한다")
    void writesAnUnsetLimitAsNullRatherThanOmittingIt() throws Exception {
        storedRules(false, null, null);
        storedSelections();

        writer.capture(JOB);

        JsonNode rules = writtenSnapshot().path("rules");
        assertThat(rules.has("maxChangedFiles")).isTrue();
        assertThat(rules.path("maxChangedFiles").isNull()).isTrue();
    }

    @Test
    @DisplayName("울타리 안의 파일만 같은 복사본에 담는다")
    void storesOnlyTheFilesInsideTheFence() throws Exception {
        storedRules(false, null, null);
        storedSelections(
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/cms",
                        true, "CMS 기능"),
                row("backend", "src/main/java/org/urizo/axmodulestudio/backend/auth",
                        false, "로그인"));

        writer.capture(JOB, List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java",
                "src/main/java/org/urizo/axmodulestudio/backend/auth/AuthService.java",
                "src/main/java/org/urizo/axmodulestudio/backend/cmsx/Other.java"));

        assertThat(writtenSnapshot().path("files"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java");
    }

    @Test
    @DisplayName("파일 목록이 없어도 빈 배열로 자리를 남긴다")
    void writesAnEmptyFileListRatherThanOmittingIt() throws Exception {
        storedRules(false, null, null);
        storedSelections(row("backend", "src/main/java/org/urizo/axmodulestudio/backend/cms",
                true, "CMS 기능"));

        writer.capture(JOB);

        assertThat(writtenSnapshot().path("files").isArray()).isTrue();
        assertThat(writtenSnapshot().path("files")).isEmpty();
    }
}
