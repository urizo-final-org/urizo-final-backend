package org.urizo.axmodulestudio.backend.coding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;

class JdbcCodingJobLifecycleRepositoryTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final JdbcCodingJobLifecycleRepository repository =
            new JdbcCodingJobLifecycleRepository(
                    jdbc,
                    transactions,
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-08-11T11:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void executeTransactionCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAnUnknownProfileVersionBeforeCreatingTheJob() {
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_version")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> repository.create(
                UUID.randomUUID(), "job.create.profile.missing", new byte[32], request()))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PROFILE_VERSION_NOT_FOUND"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAProfileThatIsNotActiveLlmOps() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("profile_key")).thenReturn("LLM_OPS");
        when(resultSet.getString("status")).thenReturn("DRAFT");
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_version")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        assertThatThrownBy(() -> repository.create(
                UUID.randomUUID(), "job.create.profile.draft", new byte[32], request()))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PROFILE_VERSION_NOT_ACTIVE"));
    }

    private static CodingJobLifecycleContract.CreateRequest request() {
        return new CodingJobLifecycleContract.CreateRequest(
                "1.0",
                PROFILE_VERSION_ID,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "plan",
                "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "coding-plan-v1",
                List.of("CHAT"),
                List.of("plan"),
                Instant.parse("2026-08-11T12:00:00Z"));
    }
}
