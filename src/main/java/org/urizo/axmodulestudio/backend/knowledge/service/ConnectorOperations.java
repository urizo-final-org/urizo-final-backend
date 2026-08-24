package org.urizo.axmodulestudio.backend.knowledge.service;

import java.util.UUID;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

public interface ConnectorOperations {

    ProductApiContract.ConnectorResponse createConnector(
            UUID projectId,
            UUID traceId,
            String key,
            ProductApiContract.CreateConnectorRequest request);

    ProductApiContract.ConnectorListResponse listConnectors(UUID projectId, UUID traceId);

    ProductApiContract.ConnectorResponse getConnector(UUID id, UUID traceId);

    ProductApiContract.ConnectorPreviewResponse previewConnector(
            UUID connectorId,
            UUID traceId,
            String key,
            ProductApiContract.ConnectorPreviewRequest request);

    ProductApiContract.ConnectorResponse activateConnectorVersion(
            UUID connectorId,
            UUID versionId,
            UUID traceId,
            String key,
            ProductApiContract.StateMutationRequest request);

    ProductApiContract.JobAcceptedResponse syncConnector(
            UUID connectorId,
            UUID traceId,
            String key,
            ProductApiContract.ConnectorSyncRequest request);
}
