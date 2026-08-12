package org.urizo.axmodulestudio.backend.coding.tool;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class CodingToolContract {

    public static final String SCHEMA_VERSION = "1.0";

    private CodingToolContract() { }

    public record Accepted(
            String schemaVersion,
            String messageType,
            UUID requestId,
            UUID toolCallId,
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            UUID executionId,
            String status,
            String statusUrl,
            int pollAfterMs,
            Instant acceptedAt) { }

    public record Succeeded(
            String schemaVersion,
            String messageType,
            UUID requestId,
            UUID toolCallId,
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            UUID executionId,
            String status,
            ResultReference result,
            String candidateSha,
            Instant completedAt) { }

    public record ResultReference(
            String mediaType,
            String resultRef,
            int sizeBytes,
            String digest) { }

    public record ResultContent(
            String schemaVersion,
            UUID requestId,
            UUID toolCallId,
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            UUID executionId,
            String mediaType,
            int sizeBytes,
            String digest,
            String content) { }

    public record JobErrorEnvelope(
            String schemaVersion,
            UUID traceId,
            UUID jobId,
            String idempotencyKey,
            ErrorDetail error) { }

    public record PreContextErrorEnvelope(
            String schemaVersion,
            UUID requestId,
            UUID traceId,
            ErrorDetail error) { }

    public record ExecutionErrorEnvelope(
            String schemaVersion,
            UUID requestId,
            UUID traceId,
            UUID executionId,
            ErrorDetail error) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) { }
}
