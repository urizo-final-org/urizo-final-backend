package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The execution-history screen asks "is the runner on" and this is the only runner-exclusive
 * trace: the stored credential is shared with the Python worker, so a database timestamp
 * cannot answer it.
 */
class CodingRunnerLivenessTest {

    private static final Instant START = Instant.parse("2026-09-02T13:00:00Z");

    /** A clock the test can move, because liveness is a judgement about elapsed time. */
    private static final class MovableClock extends Clock {
        private Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final CodingRunnerService service = new CodingRunnerService(
            jdbc, transactions, new ObjectMapper(), clock);

    @SuppressWarnings("unchecked")
    private void runnerAuthenticates() {
        when(transactions.execute(any())).thenAnswer((InvocationOnMock call) ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
        // The credential lookup finds exactly one active row; the claim queue is empty.
        doReturn(List.of(UUID.fromString("99999999-9999-4999-8999-999999999999")))
                .when(jdbc).query(contains("coding_service_credential"),
                        any(RowMapper.class), any(), any(), any());
        doReturn(List.of()).when(jdbc)
                .query(contains("coding_runner_task"), any(RowMapper.class));
        service.claim("Bearer runner-test-token",
                new org.urizo.axmodulestudio.backend.coding.dto.CodingRunnerContract
                        .ClaimRequest("1.0", UUID.randomUUID(), "local-runner"));
    }

    @Test
    void reportsNoSignalBeforeTheRunnerEverCalls() {
        CodingRunnerService.Liveness liveness = service.liveness();

        assertThat(liveness.lastSeenAt()).isNull();
        assertThat(liveness.alive()).isFalse();
    }

    @Test
    void anAuthenticatedCallOnAnEmptyQueueStillCountsAsLife() {
        runnerAuthenticates();

        CodingRunnerService.Liveness liveness = service.liveness();

        assertThat(liveness.lastSeenAt()).isEqualTo(START);
        assertThat(liveness.alive()).isTrue();
    }

    @Test
    void twoMinutesOfSilenceIsJudgedAsOff() {
        runnerAuthenticates();

        clock.now = START.plus(Duration.ofMinutes(2));
        assertThat(service.liveness().alive()).isTrue();

        clock.now = START.plus(Duration.ofMinutes(2)).plusSeconds(1);
        CodingRunnerService.Liveness liveness = service.liveness();
        assertThat(liveness.alive()).isFalse();
        // The last contact stays readable, so the screen can say how stale the signal is.
        assertThat(liveness.lastSeenAt()).isEqualTo(START);
    }
}
