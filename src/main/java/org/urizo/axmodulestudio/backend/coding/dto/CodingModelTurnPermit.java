package org.urizo.axmodulestudio.backend.coding.dto;

import java.util.Objects;
import java.util.UUID;

public record CodingModelTurnPermit(
        UUID jobId,
        String idempotencyKey,
        UUID leaseId,
        CodingModelTurnContract.Response cachedResponse) {

    public CodingModelTurnPermit {
        jobId = Objects.requireNonNull(jobId, "jobId is required");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        if ((leaseId == null) == (cachedResponse == null)) {
            throw new IllegalArgumentException("Permit must contain either a lease or a cached response.");
        }
    }

    public static CodingModelTurnPermit acquired(UUID jobId, String idempotencyKey, UUID leaseId) {
        return new CodingModelTurnPermit(jobId, idempotencyKey, leaseId, null);
    }

    public static CodingModelTurnPermit replay(
            UUID jobId,
            String idempotencyKey,
            CodingModelTurnContract.Response response) {
        return new CodingModelTurnPermit(jobId, idempotencyKey, null, response);
    }

    public boolean replay() {
        return cachedResponse != null;
    }

    @Override
    public String toString() {
        return "CodingModelTurnPermit[jobId=" + jobId
                + ", idempotencyKey=" + idempotencyKey
                + ", leaseId=" + leaseId
                + ", cachedResponse=" + (cachedResponse == null ? "absent" : "REDACTED") + "]";
    }
}
