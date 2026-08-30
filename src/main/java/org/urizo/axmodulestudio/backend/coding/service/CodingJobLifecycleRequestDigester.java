package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class CodingJobLifecycleRequestDigester {

    private final ObjectMapper objectMapper;

    public CodingJobLifecycleRequestDigester(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    byte[] create(UUID traceId, CodingJobLifecycleContract.CreateRequest request) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("commandType", "CREATE");
        canonical.put("traceId", traceId.toString());
        canonical.put("schemaVersion", request.schemaVersion());
        canonical.put("profileVersionId", request.profileVersionId().toString());
        canonical.put("actorId", request.actorId().toString());
        canonical.put("projectId", request.projectId().toString());
        canonical.put("repositoryId", request.repositoryId().toString());
        canonical.put("graphStep", request.graphStep());
        canonical.put("baseSha", request.baseSha());
        canonical.put("contextDigest", request.contextDigest());
        canonical.put("policyHash", request.policyHash());
        canonical.put("promptVersion", request.promptVersion());
        addStrings(canonical.putArray("allowedCapabilities"), request.allowedCapabilities());
        addStrings(canonical.putArray("allowedNodes"), request.allowedNodes());
        canonical.put("expiresAt", request.expiresAt().toString());
        return sha256(canonical);
    }

    byte[] transition(
            UUID jobId,
            UUID traceId,
            CodingJobLifecycleContract.TransitionRequest request) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("commandType", "TRANSITION");
        canonical.put("jobId", jobId.toString());
        canonical.put("traceId", traceId.toString());
        canonical.put("schemaVersion", request.schemaVersion());
        canonical.put("expectedStateVersion", request.expectedStateVersion());
        canonical.put("targetStatus", request.targetStatus().name());
        if (request.failure() != null) {
            ObjectNode failure = canonical.putObject("failure");
            failure.put("code", request.failure().code());
            failure.put("retryable", request.failure().retryable());
        }
        return sha256(canonical);
    }

    private static void addStrings(ArrayNode target, Iterable<String> values) {
        values.forEach(target::add);
    }

    private byte[] sha256(ObjectNode canonical) {
        byte[] encoded = null;
        try {
            encoded = objectMapper.writeValueAsBytes(canonical);
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        }
        catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Coding job request digest could not be computed.", failure);
        }
        finally {
            if (encoded != null) {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
        }
    }
}
