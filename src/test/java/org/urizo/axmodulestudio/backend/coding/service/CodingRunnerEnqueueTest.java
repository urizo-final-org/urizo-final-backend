package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue had a claim path but no way to add work, so nothing raised the
 * preview stack after a Coding candidate passed its checks.
 */
class CodingRunnerEnqueueTest {

    private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CodingRunnerService service = new CodingRunnerService(
            jdbc,
            mock(TransactionTemplate.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void addsOnePendingRowCarryingTheRequestedCommandAndPayload() {
        when(jdbc.update(any(String.class), any(), any(), any())).thenReturn(1);
        UUID taskId = service.enqueue("PREVIEW_UP",
                JsonNodeFactory.instance.objectNode().put("workspaceId", "job-1"));

        ArgumentCaptor<Object> arguments = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(any(String.class), arguments.capture(), arguments.capture(),
                arguments.capture());
        assertThat(arguments.getAllValues().get(0)).isEqualTo(taskId);
        assertThat(arguments.getAllValues().get(1)).isEqualTo("PREVIEW_UP");
        assertThat((String) arguments.getAllValues().get(2)).contains("job-1");
    }

    @Test
    void preservesACallerDerivedTaskIdForReplaySafeExternalWork() {
        UUID taskId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        when(jdbc.update(any(String.class), any(), any(), any())).thenReturn(1);

        UUID queued = service.enqueue(taskId, "CHECK_DEV_MERGE",
                JsonNodeFactory.instance.objectNode().put("prNumber", 42));

        assertThat(queued).isEqualTo(taskId);
        verify(jdbc).update(any(String.class), eq(taskId), eq("CHECK_DEV_MERGE"), any());
    }

    @Test
    void refusesACommandOutsideTheRunnerAllowlist() {
        assertThatThrownBy(() -> service.enqueue("DELETE_EVERYTHING",
                JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).update(eq("x"), new Object[0]);
    }

    @Test
    void refusesAPayloadThatIsNotAnObject() {
        assertThatThrownBy(() -> service.enqueue("BUILD",
                JsonNodeFactory.instance.arrayNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
