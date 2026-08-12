package org.urizo.axmodulestudio.backend.coding.job;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

final class CodingJobStateMachine {

    private static final Map<CodingJobLifecycleContract.Status, Set<CodingJobLifecycleContract.Status>> ALLOWED;

    static {
        Map<CodingJobLifecycleContract.Status, Set<CodingJobLifecycleContract.Status>> allowed =
                new EnumMap<>(CodingJobLifecycleContract.Status.class);
        allowed.put(CodingJobLifecycleContract.Status.PENDING, EnumSet.of(
                CodingJobLifecycleContract.Status.RUNNING,
                CodingJobLifecycleContract.Status.FAILED,
                CodingJobLifecycleContract.Status.CANCELLED));
        allowed.put(CodingJobLifecycleContract.Status.RUNNING, EnumSet.of(
                CodingJobLifecycleContract.Status.WAITING_APPROVAL,
                CodingJobLifecycleContract.Status.COMPLETED,
                CodingJobLifecycleContract.Status.FAILED,
                CodingJobLifecycleContract.Status.CANCELLED));
        allowed.put(CodingJobLifecycleContract.Status.WAITING_APPROVAL, EnumSet.of(
                CodingJobLifecycleContract.Status.RUNNING,
                CodingJobLifecycleContract.Status.FAILED,
                CodingJobLifecycleContract.Status.CANCELLED));
        ALLOWED = Map.copyOf(allowed);
    }

    private CodingJobStateMachine() {
    }

    static void requireTransition(
            CodingJobLifecycleContract.Status current,
            CodingJobLifecycleContract.Status target,
            Instant now,
            Instant expiresAt) {
        if (current.terminal()) {
            throw conflict("JOB_TERMINAL", "Terminal coding jobs are immutable.");
        }
        if (target == CodingJobLifecycleContract.Status.EXPIRED) {
            if (now.isBefore(expiresAt)) {
                throw conflict("JOB_NOT_EXPIRED", "The coding job has not reached its expiry.");
            }
            return;
        }
        if (!now.isBefore(expiresAt)) {
            throw conflict("JOB_EXPIRED", "The coding job must transition to EXPIRED.");
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw conflict(
                    "JOB_STATE_TRANSITION_DENIED",
                    "The requested coding job state transition is not allowed.");
        }
    }

    private static CodingJobLifecycleException conflict(String code, String message) {
        return new CodingJobLifecycleException(code, message, HttpStatus.CONFLICT);
    }
}
