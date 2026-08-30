package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class CodingJobStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-08-11T11:00:00Z");
    private static final Instant FUTURE = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void permitsOnlyTheDeclaredLifecycleGraph() {
        CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.PENDING,
                CodingJobLifecycleContract.Status.RUNNING,
                NOW,
                FUTURE);
        CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.RUNNING,
                CodingJobLifecycleContract.Status.WAITING_APPROVAL,
                NOW,
                FUTURE);
        CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.WAITING_APPROVAL,
                CodingJobLifecycleContract.Status.RUNNING,
                NOW,
                FUTURE);
        CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.WAITING_APPROVAL,
                CodingJobLifecycleContract.Status.CANCELLED,
                NOW,
                FUTURE);
        CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.RUNNING,
                CodingJobLifecycleContract.Status.COMPLETED,
                NOW,
                FUTURE);

        assertThatThrownBy(() -> CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.WAITING_APPROVAL,
                CodingJobLifecycleContract.Status.COMPLETED,
                NOW,
                FUTURE))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("JOB_STATE_TRANSITION_DENIED"));
    }

    @Test
    void terminalJobsAreImmutableAndExpiryIsClockBound() {
        assertThatThrownBy(() -> CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.COMPLETED,
                CodingJobLifecycleContract.Status.RUNNING,
                NOW,
                FUTURE))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("JOB_TERMINAL"));

        assertThatThrownBy(() -> CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.RUNNING,
                CodingJobLifecycleContract.Status.EXPIRED,
                NOW,
                FUTURE))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("JOB_NOT_EXPIRED"));

        CodingJobStateMachine.requireTransition(
                CodingJobLifecycleContract.Status.RUNNING,
                CodingJobLifecycleContract.Status.EXPIRED,
                FUTURE,
                FUTURE);
    }
}
