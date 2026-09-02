package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
     * read of the paths and one of the rules.
     */
    private void storedRules(boolean allowNewDependency, Integer files, Integer lines) {
        ObjectNode rules = mapper.createObjectNode();
        rules.put("allowNewDependency", allowNewDependency);
        rules.put("maxChangedFiles", files);
        rules.put("maxChangedLines", lines);
        doReturn(List.of(rules)).when(jdbc).query(anyString(), any(RowMapper.class));
    }

    private JsonNode writtenSnapshot() throws Exception {
        ArgumentCaptor<Object> arguments = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), eq(JOB), arguments.capture());
        return mapper.readTree((String) arguments.getValue());
    }

    @Test
    @DisplayName("켜져 있는 경로만 저장소를 붙여 복사한다")
    void copiesOnlyTheEnabledPaths() throws Exception {
        storedRules(false, null, null);
        storedRules(false, null, null);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(
                List.of("backend:src/main/java/org/urizo/axmodulestudio/backend/cms",
                        "frontend:src/features/cms"));

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
    @DisplayName("아무것도 켜져 있지 않아도 빈 목록을 기록한다. 기록 없음과 구분되어야 한다")
    void writesAnEmptySnapshotRatherThanNothing() throws Exception {
        storedRules(false, null, null);
        storedRules(false, null, null);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        assertThat(writer.capture(JOB)).isEmpty();

        assertThat(writtenSnapshot().path("allowedPaths")).isEmpty();
    }

    @Test
    @DisplayName("같은 Job 을 다시 초기화해도 복사본을 덮어쓰지 않는다")
    void doesNotOverwriteAnExistingSnapshot() {
        storedRules(false, null, null);
        storedRules(false, null, null);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        writer.capture(JOB);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(JOB), any());
        assertThat(sql.getValue()).contains("ON CONFLICT (job_id) DO NOTHING");
    }

    @Test
    @DisplayName("돌려주는 목록은 바꿀 수 없다")
    void returnsAnUnmodifiableList() {
        storedRules(false, null, null);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(new java.util.ArrayList<>(List.of("backend:src")));

        List<String> captured = writer.capture(JOB);

        assertThat(captured).isUnmodifiable();
    }

    @Test
    @DisplayName("경로와 함께 부가 규칙도 같은 복사본에 담는다")
    void copiesTheRulesAlongsideThePaths() throws Exception {
        storedRules(true, 12, 400);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

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
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        writer.capture(JOB);

        JsonNode rules = writtenSnapshot().path("rules");
        assertThat(rules.has("maxChangedFiles")).isTrue();
        assertThat(rules.path("maxChangedFiles").isNull()).isTrue();
    }
}
