package org.urizo.axmodulestudio.backend.product;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.connector.ConnectorOperations;
import org.urizo.axmodulestudio.backend.job.ProductJobOperations;
import org.urizo.axmodulestudio.backend.knowledge.KnowledgeOperations;
import org.urizo.axmodulestudio.backend.project.ProjectOperations;
import org.urizo.axmodulestudio.backend.rag.RagOperations;

@RestController
@Validated
@Profile("local-full")
@RequestMapping("/api")
public class ProductApiController {

    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final ProjectOperations projects;
    private final ConnectorOperations connectors;
    private final KnowledgeOperations knowledge;
    private final RagOperations rag;
    private final ProductJobOperations jobs;

    ProductApiController(
            ProjectOperations projects,
            ConnectorOperations connectors,
            KnowledgeOperations knowledge,
            RagOperations rag,
            ProductJobOperations jobs) {
        this.projects = projects;
        this.connectors = connectors;
        this.knowledge = knowledge;
        this.rag = rag;
        this.jobs = jobs;
    }

    @PostMapping("/projects")
    ResponseEntity<ProductApiContract.ProjectResponse> createProject(
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateProjectRequest body,
            HttpServletRequest request) {
        ProductApiContract.ProjectResponse response = projects.createProject(trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/projects/" + response.projectId())
                .body(response);
    }

    @GetMapping("/projects")
    ProductApiContract.ProjectListResponse listProjects(HttpServletRequest request) {
        return projects.listProjects(trace(request));
    }

    @GetMapping("/projects/{projectId}")
    ProductApiContract.ProjectResponse getProject(
            @PathVariable UUID projectId, HttpServletRequest request) {
        return projects.getProject(projectId, trace(request));
    }

    @PostMapping("/projects/{projectId}/connectors")
    ResponseEntity<ProductApiContract.ConnectorResponse> createConnector(
            @PathVariable UUID projectId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateConnectorRequest body,
            HttpServletRequest request) {
        ProductApiContract.ConnectorResponse response = connectors.createConnector(
                projectId, trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/connectors/" + response.connectorId())
                .body(response);
    }

    @GetMapping("/projects/{projectId}/connectors")
    ProductApiContract.ConnectorListResponse listConnectors(
            @PathVariable UUID projectId, HttpServletRequest request) {
        return connectors.listConnectors(projectId, trace(request));
    }

    @GetMapping("/connectors/{connectorId}")
    ProductApiContract.ConnectorResponse getConnector(
            @PathVariable UUID connectorId, HttpServletRequest request) {
        return connectors.getConnector(connectorId, trace(request));
    }

    @PostMapping("/connectors/{connectorId}/preview")
    ProductApiContract.ConnectorPreviewResponse previewConnector(
            @PathVariable UUID connectorId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.ConnectorPreviewRequest body,
            HttpServletRequest request) {
        return connectors.previewConnector(connectorId, trace(request), key, body);
    }

    @PostMapping("/connectors/{connectorId}/versions/{connectorVersionId}/activate")
    ProductApiContract.ConnectorResponse activateConnectorVersion(
            @PathVariable UUID connectorId,
            @PathVariable UUID connectorVersionId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return connectors.activateConnectorVersion(
                connectorId, connectorVersionId, trace(request), key, body);
    }

    @PostMapping("/connectors/{connectorId}/sync")
    ResponseEntity<ProductApiContract.JobAcceptedResponse> syncConnector(
            @PathVariable UUID connectorId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.ConnectorSyncRequest body,
            HttpServletRequest request) {
        ProductApiContract.JobAcceptedResponse response = connectors.syncConnector(
                connectorId, trace(request), key, body);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, response.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(response);
    }

    @PostMapping("/knowledge-bases")
    ResponseEntity<ProductApiContract.KnowledgeBaseResponse> createKnowledgeBase(
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateKnowledgeBaseRequest body,
            HttpServletRequest request) {
        ProductApiContract.KnowledgeBaseResponse response = knowledge.createKnowledgeBase(
                trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/knowledge-bases/" + response.knowledgeBaseId())
                .body(response);
    }

    @GetMapping("/knowledge-bases")
    ProductApiContract.KnowledgeBaseListResponse listKnowledgeBases(
            @RequestParam UUID projectId, HttpServletRequest request) {
        return knowledge.listKnowledgeBases(projectId, trace(request));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}")
    ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(
            @PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        return knowledge.getKnowledgeBase(knowledgeBaseId, trace(request));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/versions")
    ResponseEntity<ProductApiContract.JobAcceptedResponse> startKnowledgeBuild(
            @PathVariable UUID knowledgeBaseId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StartKnowledgeBuildRequest body,
            HttpServletRequest request) {
        ProductApiContract.JobAcceptedResponse response = knowledge.startKnowledgeBuild(
                knowledgeBaseId, trace(request), key, body);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, response.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(response);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/versions")
    ProductApiContract.KnowledgeVersionListResponse listKnowledgeVersions(
            @PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        return knowledge.listKnowledgeVersions(knowledgeBaseId, trace(request));
    }

    @GetMapping("/knowledge-versions/{knowledgeVersionId}")
    ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(
            @PathVariable UUID knowledgeVersionId, HttpServletRequest request) {
        return knowledge.getKnowledgeVersion(knowledgeVersionId, trace(request));
    }

    @PostMapping("/knowledge-versions/{knowledgeVersionId}/activate")
    ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            @PathVariable UUID knowledgeVersionId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return knowledge.activateKnowledgeVersion(knowledgeVersionId, trace(request), key, body);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/rollback")
    ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            @PathVariable UUID knowledgeBaseId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.RollbackKnowledgeRequest body,
            HttpServletRequest request) {
        return knowledge.rollbackKnowledgeVersion(knowledgeBaseId, trace(request), key, body);
    }

    @PostMapping("/projects/{projectId}/chatbots")
    ResponseEntity<ProductApiContract.ChatbotResponse> createChatbot(
            @PathVariable UUID projectId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateChatbotRequest body,
            HttpServletRequest request) {
        ProductApiContract.ChatbotResponse response = rag.createChatbot(
                projectId, trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/chatbots/" + response.chatbotId())
                .body(response);
    }

    @GetMapping("/projects/{projectId}/chatbots")
    ProductApiContract.ChatbotListResponse listChatbots(
            @PathVariable UUID projectId, HttpServletRequest request) {
        return rag.listChatbots(projectId, trace(request));
    }

    @GetMapping("/chatbots/{chatbotId}")
    ProductApiContract.ChatbotResponse getChatbot(
            @PathVariable UUID chatbotId, HttpServletRequest request) {
        return rag.getChatbot(chatbotId, trace(request));
    }

    @PostMapping("/chatbots/{chatbotId}/query")
    ProductApiContract.RagQueryResponse queryChatbot(
            @PathVariable UUID chatbotId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.RagQueryRequest body,
            HttpServletRequest request) {
        return rag.query(chatbotId, trace(request), key, body);
    }

    @GetMapping("/agent-jobs/{jobId}")
    ProductApiContract.AgentJobResponse getJob(
            @PathVariable UUID jobId, HttpServletRequest request) {
        return jobs.getJob(jobId, trace(request));
    }

    @GetMapping("/agent-jobs")
    ProductApiContract.AgentJobListResponse listJobs(
            @RequestParam UUID projectId, HttpServletRequest request) {
        return jobs.listJobs(projectId, trace(request));
    }

    @PostMapping("/agent-jobs/{jobId}/cancel")
    ProductApiContract.AgentJobResponse cancelJob(
            @PathVariable UUID jobId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return jobs.cancelJob(jobId, trace(request), key, body);
    }

    @PostMapping("/agent-jobs/{jobId}/retry")
    ProductApiContract.AgentJobResponse retryJob(
            @PathVariable UUID jobId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return jobs.retryJob(jobId, trace(request), key, body);
    }

    private static UUID trace(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}
