package org.urizo.axmodulestudio.backend.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read projections, never approval commands or a second Job state machine. */
public final class HistoryContract {
    private HistoryContract() { }

    public enum Domain { RAG, LLM_OPS, NATURAL_CMS }
    public enum Category { ALL, CMS, AI }

    public record Page(List<Entry> items, String nextCursor, Instant observedAt) { }

    /**
     * occurredAt is an actual decision/change timestamp, not a guessed updatedAt.
     * VERSION_STATE and LATEST_ONLY are explicitly incomplete decision histories.
     * No candidate SHA, diff, model payload, credential or tool arguments are exposed.
     */
    public record Entry(
            String id, String domain, String kind, String title,
            String targetType, String targetId, UUID jobId,
            String status, String jobStatus, String stage, Integer attempt, Integer stateVersion,
            UUID actorId, String actorName, String actorRole,
            Instant createdAt, Instant occurredAt, Instant startedAt, Instant updatedAt, Instant finishedAt,
            String feedback, String errorCode, String coverage) { }
}
