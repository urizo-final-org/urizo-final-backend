package org.urizo.axmodulestudio.backend.coding.integration;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Executes one server-selected deployment target without accepting commands from the graph. */
public interface DeploymentAdapter {

    String adapterKey();

    String targetKey();

    String configDigest();

    DeploymentOutcome deploy(UUID executionId, JsonNode payload);

    enum Status { PENDING, COMPLETED, BLOCKED }

    record DeploymentOutcome(Status status, JsonNode payload, String errorCode) { }
}
