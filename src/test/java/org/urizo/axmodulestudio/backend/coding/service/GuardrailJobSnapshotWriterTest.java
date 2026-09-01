package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class GuardrailJobSnapshotWriterTest {

    private static final UUID JOB = UUID.fromString("31313131-3131-4131-8131-313131313131");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GuardrailJobSnapshotWriter writer =
            new GuardrailJobSnapshotWriter(jdbc, mapper);

    private JsonNode writtenSnapshot() throws Exception {
        ArgumentCaptor<Object> arguments = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), eq(JOB), arguments.capture());
        return mapper.readTree((String) arguments.getValue());
    }

    @Test
    @DisplayName("켜져 있는 경로만 저장소를 붙여 복사한다")
    void copiesOnlyTheEnabledPaths() throws Exception {
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
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        assertThat(writer.capture(JOB)).isEmpty();

        assertThat(writtenSnapshot().path("allowedPaths")).isEmpty();
    }

    @Test
    @DisplayName("같은 Job 을 다시 초기화해도 복사본을 덮어쓰지 않는다")
    void doesNotOverwriteAnExistingSnapshot() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        writer.capture(JOB);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(JOB), any());
        assertThat(sql.getValue()).contains("ON CONFLICT (job_id) DO NOTHING");
    }

    @Test
    @DisplayName("돌려주는 목록은 바꿀 수 없다")
    void returnsAnUnmodifiableList() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(new java.util.ArrayList<>(List.of("backend:src")));

        List<String> captured = writer.capture(JOB);

        assertThat(captured).isUnmodifiable();
    }
}
