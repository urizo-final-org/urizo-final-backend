package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.util.UUID;

/** Request-scoped, allowlisted business identifiers for one actual model call. */
public final class ModelObservationScope implements AutoCloseable {

    private static final ThreadLocal<Metadata> CURRENT = new ThreadLocal<>();

    private final Metadata previous;

    private ModelObservationScope(Metadata metadata) {
        previous = CURRENT.get();
        CURRENT.set(previous == null ? metadata : new Metadata(
                metadata.jobId() == null ? previous.jobId() : metadata.jobId(),
                metadata.traceId() == null ? previous.traceId() : metadata.traceId(),
                metadata.profileVersionId() == null
                        ? previous.profileVersionId() : metadata.profileVersionId(),
                metadata.nodeId() == null ? previous.nodeId() : metadata.nodeId()));
    }

    public static ModelObservationScope open(
            UUID jobId,
            UUID traceId,
            UUID profileVersionId,
            String nodeId) {
        return new ModelObservationScope(new Metadata(
                jobId, traceId, profileVersionId, nodeId));
    }

    static Metadata current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        }
        else {
            CURRENT.set(previous);
        }
    }

    record Metadata(UUID jobId, UUID traceId, UUID profileVersionId, String nodeId) { }
}
