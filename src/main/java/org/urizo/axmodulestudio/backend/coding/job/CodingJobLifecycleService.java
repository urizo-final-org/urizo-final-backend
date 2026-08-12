package org.urizo.axmodulestudio.backend.coding.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("dev & coding-job-local-fixture")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public class CodingJobLifecycleService {

    private static final Duration MAX_LIFETIME = Duration.ofHours(24);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    private final CodingJobLifecycleRepository repository;
    private final CodingJobLifecycleRequestDigester digester;
    private final Clock clock;

    CodingJobLifecycleService(
            CodingJobLifecycleRepository repository,
            CodingJobLifecycleRequestDigester digester,
            Clock clock) {
        this.repository = repository;
        this.digester = digester;
        this.clock = clock;
    }

    public CodingJobLifecycleContract.JobResponse create(
            UUID traceId,
            String idempotencyKey,
            CodingJobLifecycleContract.CreateRequest request) {
        Objects.requireNonNull(traceId, "traceId is required");
        requireIdempotencyKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (!request.expiresAt().isAfter(now)
                || request.expiresAt().isAfter(now.plus(MAX_LIFETIME))) {
            throw new CodingJobLifecycleException(
                    "JOB_EXPIRY_INVALID",
                    "expiresAt must be in the future and no more than 24 hours away.",
                    HttpStatus.BAD_REQUEST);
        }
        byte[] digest = digester.create(traceId, request);
        try {
            return repository.create(traceId, idempotencyKey, digest, request);
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public CodingJobLifecycleContract.JobResponse find(UUID jobId, UUID traceId) {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(traceId, "traceId is required");
        return repository.find(jobId, traceId);
    }

    public CodingJobLifecycleContract.JobResponse transition(
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            CodingJobLifecycleContract.TransitionRequest request) {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(traceId, "traceId is required");
        requireIdempotencyKey(idempotencyKey);
        byte[] digest = digester.transition(jobId, traceId, request);
        try {
            return repository.transition(jobId, traceId, idempotencyKey, digest, request);
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new CodingJobLifecycleException(
                    "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key does not satisfy the coding job contract.",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
