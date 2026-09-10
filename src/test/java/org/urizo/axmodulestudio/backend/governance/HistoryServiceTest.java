package org.urizo.axmodulestudio.backend.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class HistoryServiceTest {
    final Instant at = Instant.parse("2026-09-10T05:00:00Z");
    final JdbcTemplate product = mock(JdbcTemplate.class);
    final JdbcTemplate coding = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
    final HistoryService service = new HistoryService(product, provider, Clock.fixed(at, ZoneOffset.UTC));

    @Test
    void mergesBoundedSourcesWithStableCursorAndLiteralSearch() throws Exception {
        when(provider.getIfAvailable()).thenReturn(coding);
        List<String> statements = new ArrayList<>();
        List<Object[]> parameters = new ArrayList<>();
        answer(product, statements, parameters);
        answer(coding, statements, parameters);
        var first = service.runs(HistoryContract.Category.ALL, "  a%_  ", null, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.items().get(0).id()).startsWith("product-job:");
        assertThat(first.items().get(1).id()).startsWith("natural-job:");
        assertThat(first.observedAt()).isEqualTo(at);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(statements).hasSize(4).allSatisfy(sql -> {
            assertThat(sql).contains("position(lower(?)", "COLLATE \"C\" DESC LIMIT ?");
            assertThat(sql).doesNotContain("a%_");
        });
        assertThat(parameters).allSatisfy(args -> assertThat(args).containsExactly("a%_", 3));
        statements.clear(); parameters.clear();
        service.runs(HistoryContract.Category.ALL, "a%_", first.nextCursor(), 2);
        assertThat(parameters).allSatisfy(args -> {
            assertThat(args).hasSize(5);
            assertThat(args[1]).isEqualTo(Timestamp.from(at));
            assertThat(args[3]).isEqualTo(first.items().get(1).id());
        });
        assertThatThrownBy(() -> service.runs(HistoryContract.Category.CMS, "a%_", first.nextCursor(), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.runs(HistoryContract.Category.ALL, "other", first.nextCursor(), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void answer(JdbcTemplate jdbc, List<String> statements, List<Object[]> parameters) throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            statements.add(sql);
            Object[] args = (Object[]) call.getRawArguments()[2];
            parameters.add(args);
            String prefix = sql.contains("cms_change_history") ? "cms-change" : sql.contains("natural_cms_job") ? "natural-job" : sql.contains("coding_job") ? "coding-job" : "product-job";
            ResultSet rs = mock(ResultSet.class);
            when(rs.getTimestamp("sort_at")).thenReturn(Timestamp.from(at));
            when(rs.getString("id")).thenReturn(prefix + ":00000000-0000-0000-0000-000000000001");
            when(rs.getString("status")).thenReturn("ACTIVE");
            RowMapper mapper = call.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    @Test
    void cmsOnlyDoesNotRequireCodingDatasourceAndEmptyIsSuccessful() {
        var result = service.runs(HistoryContract.Category.CMS, "", null, 25);
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        verifyNoInteractions(provider, coding);
    }

    @Test
    void unavailableCodingIsNotSilentlyAnEmptyHistory() {
        assertThatThrownBy(() -> service.approvals(HistoryContract.Domain.LLM_OPS, "", null, 25))
                .isInstanceOf(HistoryService.HistoryUnavailableException.class);
    }

    @Test
    void rejectsInvalidLimitsSearchAndCursorsBeforeReads() {
        for (int limit : List.of(0, 101)) assertThatThrownBy(() -> service.runs(HistoryContract.Category.ALL, "", null, limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.runs(HistoryContract.Category.ALL, "x".repeat(201), null, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.runs(HistoryContract.Category.ALL, "", "bad!", 25)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(product, provider);
    }

    @Test
    void projectionDoesNotInventNaturalApprovalTimeOrReturnPrivilegedPayloads() {
        assertThat(HistoryService.NATURAL_APPROVALS).contains("NULL::timestamptz AS occurred_at", "'LATEST_ONLY' AS coverage");
        assertThat(HistoryService.RAG_APPROVALS).contains("'VERSION_STATE' AS coverage", "NULL::uuid AS actor_id");
        assertThat(HistoryService.NATURAL_JOBS).contains("NULL::timestamptz AS finished_at");
        assertThat(HistoryService.CODING_APPROVALS).doesNotContain("response_json", "candidate_sha", "validation_hash");
        assertThat(HistoryService.CODING_APPROVALS).contains("d.job_id || ':' || d.pipeline_attempt || ':' || d.approval_id");
    }
}
