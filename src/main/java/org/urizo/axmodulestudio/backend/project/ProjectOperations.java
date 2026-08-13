package org.urizo.axmodulestudio.backend.project;

import java.util.UUID;

import org.urizo.axmodulestudio.backend.product.ProductApiContract;

public interface ProjectOperations {

    ProductApiContract.ProjectResponse createProject(
            UUID traceId, String key, ProductApiContract.CreateProjectRequest request);

    ProductApiContract.ProjectListResponse listProjects(UUID traceId);

    ProductApiContract.ProjectResponse getProject(UUID id, UUID traceId);
}
