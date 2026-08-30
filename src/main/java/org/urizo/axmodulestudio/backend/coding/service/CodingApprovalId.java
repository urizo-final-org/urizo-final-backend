package org.urizo.axmodulestudio.backend.coding.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

/** Cross-runtime UUIDv5 identity for one feature-owned Coding approval round. */
public final class CodingApprovalId {

    private static final UUID NAMESPACE_URL =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private CodingApprovalId() { }

    public static UUID forStage(
            UUID jobId,
            int pipelineAttempt,
            String nodeId,
            CodingHandlerContract.ApprovalStage stage,
            int stageRound) {
        String name = "axms:coding-approval:" + jobId + ":" + pipelineAttempt + ":"
                + nodeId + ":" + stage.name() + ":" + stageRound;
        return uuid5(NAMESPACE_URL, name);
    }

    static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer namespaceBytes = ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits());
            sha1.update(namespaceBytes.array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer value = ByteBuffer.wrap(hash);
            return new UUID(value.getLong(), value.getLong());
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-1 is unavailable for UUIDv5.", failure);
        }
    }
}
