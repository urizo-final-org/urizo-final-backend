package org.urizo.axmodulestudio.backend.coding.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;

/** Completes the existing expiry transition without changing approval or retry policy. */
@Component
@Profile("local-full & dev & coding-job-local-fixture")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class CodingJobExpiryReconciler {

    private static final Logger log = LoggerFactory.getLogger(CodingJobExpiryReconciler.class);
    private final JdbcTemplate jdbc;
    private final CodingJobLifecycleService lifecycle;
    private final Clock clock;

    public CodingJobExpiryReconciler(
            @Qualifier("codingJobLifecycleJdbcTemplate") JdbcTemplate jdbc,
            CodingJobLifecycleService lifecycle,
            Clock clock) {
        this.jdbc = jdbc;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    // Scheduling is already enabled by the local-full runtime configuration.
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void reconcile() {
        List<Candidate> candidates = jdbc.query("""
                SELECT job_id, trace_id, state_version
                FROM app.coding_job
                WHERE authority_source = 'SPRING_CONTROL_PLANE'
                  AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL')
                  AND expires_at <= ?
                ORDER BY expires_at, job_id
                LIMIT 100
                """,
                (row, index) -> new Candidate(
                        row.getObject("job_id", UUID.class),
                        row.getObject("trace_id", UUID.class),
                        row.getInt("state_version")),
                Timestamp.from(Instant.now(clock)));
        for (Candidate candidate : candidates) {
            try {
                // Stable across scans/instances; the lifecycle owns locking, replay and audit.
                lifecycle.transition(candidate.jobId(), candidate.traceId(),
                        "job.expire." + candidate.jobId() + ".v" + candidate.stateVersion(),
                        new CodingJobLifecycleContract.TransitionRequest(
                                CodingJobLifecycleContract.SCHEMA_VERSION,
                                candidate.stateVersion(), CodingJobLifecycleContract.Status.EXPIRED, null));
            }
            catch (CodingJobLifecycleException failure) {
                switch (failure.code()) {
                    case "JOB_STATE_VERSION_CONFLICT", "JOB_TERMINAL", "JOB_NOT_EXPIRED", "JOB_NOT_FOUND" ->
                        log.debug("Coding job expiry deferred: jobId={}, code={}",
                                candidate.jobId(), failure.code());
                    default -> log.warn("Coding job expiry failed: jobId={}, code={}",
                            candidate.jobId(), failure.code());
                }
            }
            catch (RuntimeException failure) {
                // One failed candidate must not prevent the rest of the batch from expiring.
                log.warn("Coding job expiry failed: jobId={}, failureType={}",
                        candidate.jobId(), failure.getClass().getSimpleName());
            }
        }
    }

    private record Candidate(UUID jobId, UUID traceId, int stateVersion) { }
}
