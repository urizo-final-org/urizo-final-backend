package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailRuleContract;

class GuardrailRuleServiceTest {

    private static final UUID JOB = UUID.fromString("41414141-4141-4141-8141-414141414141");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GuardrailRuleService service =
            new GuardrailRuleService(jdbc, new ObjectMapper());

    private void storedRow(boolean allowNewDependency, Integer files, Integer lines) {
        doReturn(List.of(new GuardrailRuleContract.Rules(allowNewDependency, files, lines)))
                .when(jdbc).query(anyString(), any(RowMapper.class));
    }

    private void snapshotJson(String json) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(JOB)))
                .thenReturn(json == null ? List.of() : java.util.Collections.singletonList(json));
    }

    @Test
    @DisplayName("설정을 아직 안 건드렸으면 라이브러리 금지 · 상한 없음이다")
    void readsTheSeededRow() {
        storedRow(false, null, null);

        GuardrailRuleContract.Rules rules = service.rules();

        assertThat(rules.allowNewDependency()).isFalse();
        assertThat(rules.maxChangedFiles()).isNull();
        assertThat(rules.maxChangedLines()).isNull();
    }

    /**
     * The migration seeds the row and nobody holds INSERT or DELETE, so an absent row is a broken
     * schema rather than "not configured yet". Treating it as the latter would silently drop the
     * whole third layer.
     */
    @Test
    @DisplayName("줄이 없으면 설정 안 함이 아니라 고장으로 본다")
    void refusesToInventRulesWhenTheRowIsMissing() {
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class));

        assertThatThrownBy(service::rules)
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("저장은 언제나 그 한 줄을 고치는 것이지 새로 만드는 게 아니다")
    void savesByUpdatingTheOneRow() {
        storedRow(true, 10, 300);
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        service.save(new GuardrailRuleContract.Rules(true, 10, 300));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(true), eq(10), eq(300));
        assertThat(sql.getValue()).startsWith("UPDATE app.guardrail_rule");
    }

    @Test
    @DisplayName("0 이나 음수 상한은 거절한다. 안 정함은 null 로만 표현한다")
    void refusesANonPositiveLimit() {
        assertThatThrownBy(() -> service.save(
                new GuardrailRuleContract.Rules(false, 0, null)))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("maxChangedFiles");

        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("Job 복사본의 규칙을 그대로 읽는다")
    void readsTheRulesCopiedForAJob() {
        snapshotJson("{\"allowNewDependency\":true,\"maxChangedFiles\":5,"
                + "\"maxChangedLines\":null}");

        GuardrailRuleContract.Rules rules = service.jobRules(JOB).orElseThrow();

        assertThat(rules.allowNewDependency()).isTrue();
        assertThat(rules.maxChangedFiles()).isEqualTo(5);
        assertThat(rules.maxChangedLines()).isNull();
    }

    /** A job created before the rules existed never ran under them. */
    @Test
    @DisplayName("복사본이 없는 옛 Job 은 비어 있는 채로 돌려준다")
    void reportsNoRulesForAJobWithoutASnapshot() {
        snapshotJson(null);

        assertThat(service.jobRules(JOB)).isEmpty();
    }

    @Test
    @DisplayName("경로만 담긴 옛 복사본도 규칙 없음으로 읽는다")
    void reportsNoRulesWhenTheSnapshotPredatesThem() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(JOB)))
                .thenReturn(java.util.Collections.singletonList(null));

        assertThat(service.jobRules(JOB)).isEmpty();
    }

    @Test
    @DisplayName("복사본이 깨져 있으면 규칙 없음으로 넘기지 않고 실패시킨다")
    void refusesAnUnreadableSnapshot() {
        snapshotJson("not json");

        assertThatThrownBy(() -> service.jobRules(JOB))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("cannot be read");
    }
}
