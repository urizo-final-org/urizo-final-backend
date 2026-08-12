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

@RestController
@Validated
@Profile("local-full")
@RequestMapping("/api")
public class ProductApiController {

    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final ProductService service;

    ProductApiController(ProductService service) {
        this.service = service;
    }

    @PostMapping("/projects")
    ResponseEntity<ProductApiContract.ProjectResponse> createProject(
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateProjectRequest body,
            HttpServletRequest request) {
        ProductApiContract.ProjectResponse response = service.createProject(trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/projects/" + response.projectId())
                .body(response);
    }

    @GetMapping("/projects")
    ProductApiContract.ProjectListResponse listProjects(HttpServletRequest request) {
        return service.listProjects(trace(request));
    }

    @GetMapping("/projects/{projectId}")
    ProductApiContract.ProjectResponse getProject(
            @PathVariable UUID projectId, HttpServletRequest request) {
        return service.getProject(projectId, trace(request));
    }

    @PostMapping("/projects/{projectId}/connectors")
    ResponseEntity<ProductApiContract.ConnectorResponse> createConnector(
            @PathVariable UUID projectId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateConnectorRequest body,
            HttpServletRequest request) {
        ProductApiContract.ConnectorResponse response = service.createConnector(
                projectId, trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/connectors/" + response.connectorId())
                .body(response);
    }

    @GetMapping("/projects/{projectId}/connectors")
    ProductApiContract.ConnectorListResponse listConnectors(
            @PathVariable UUID projectId, HttpServletRequest request) {
        return service.listConnectors(projectId, trace(request));
    }

    @GetMapping("/connectors/{connectorId}")
    ProductApiContract.ConnectorResponse getConnector(
            @PathVariable UUID connectorId, HttpServletRequest request) {
        return service.getConnector(connectorId, trace(request));
    }

    @PostMapping("/connectors/{connectorId}/preview")
    ProductApiContract.ConnectorPreviewResponse previewConnector(
            @PathVariable UUID connectorId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.ConnectorPreviewRequest body,
            HttpServletRequest request) {
        return service.previewConnector(connectorId, trace(request), key, body);
    }

    @PostMapping("/connectors/{connectorId}/versions/{connectorVersionId}/activate")
    ProductApiContract.ConnectorResponse activateConnectorVersion(
            @PathVariable UUID connectorId,
            @PathVariable UUID connectorVersionId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return service.activateConnectorVersion(
                connectorId, connectorVersionId, trace(request), key, body);
    }

    @PostMapping("/connectors/{connectorId}/sync")
    ResponseEntity<ProductApiContract.JobAcceptedResponse> syncConnector(
            @PathVariable UUID connectorId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.ConnectorSyncRequest body,
            HttpServletRequest request) {
        ProductApiContract.JobAcceptedResponse response = service.syncConnector(
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
        ProductApiContract.KnowledgeBaseResponse response = service.createKnowledgeBase(
                trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/knowledge-bases/" + response.knowledgeBaseId())
                .body(response);
    }

    @GetMapping("/knowledge-bases")
    ProductApiContract.KnowledgeBaseListResponse listKnowledgeBases(
            @RequestParam UUID projectId, HttpServletRequest request) {
        return service.listKnowledgeBases(projectId, trace(request));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}")
    ProductApiContract.KnowledgeBaseResponse getKnowledgeBase(
            @PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        return service.getKnowledgeBase(knowledgeBaseId, trace(request));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/versions")
    ResponseEntity<ProductApiContract.JobAcceptedResponse> startKnowledgeBuild(
            @PathVariable UUID knowledgeBaseId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StartKnowledgeBuildRequest body,
            HttpServletRequest request) {
        ProductApiContract.JobAcceptedResponse response = service.startKnowledgeBuild(
                knowledgeBaseId, trace(request), key, body);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, response.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(response);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/versions")
    ProductApiContract.KnowledgeVersionListResponse listKnowledgeVersions(
            @PathVariable UUID knowledgeBaseId, HttpServletRequest request) {
        return service.listKnowledgeVersions(knowledgeBaseId, trace(request));
    }

    @GetMapping("/knowledge-versions/{knowledgeVersionId}")
    ProductApiContract.KnowledgeVersionResponse getKnowledgeVersion(
            @PathVariable UUID knowledgeVersionId, HttpServletRequest request) {
        return service.getKnowledgeVersion(knowledgeVersionId, trace(request));
    }

    @PostMapping("/knowledge-versions/{knowledgeVersionId}/activate")
    ProductApiContract.KnowledgeVersionResponse activateKnowledgeVersion(
            @PathVariable UUID knowledgeVersionId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return service.activateKnowledgeVersion(knowledgeVersionId, trace(request), key, body);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/rollback")
    ProductApiContract.KnowledgeVersionResponse rollbackKnowledgeVersion(
            @PathVariable UUID knowledgeBaseId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.RollbackKnowledgeRequest body,
            HttpServletRequest request) {
        return service.rollbackKnowledgeVersion(knowledgeBaseId, trace(request), key, body);
    }

    @PostMapping("/projects/{projectId}/chatbots")
    ResponseEntity<ProductApiContract.ChatbotResponse> createChatbot(
            @PathVariable UUID projectId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.CreateChatbotRequest body,
            HttpServletRequest request) {
        ProductApiContract.ChatbotResponse response = service.createChatbot(
                projectId, trace(request), key, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/chatbots/" + response.chatbotId())
                .body(response);
    }

    @GetMapping("/projects/{projectId}/chatbots")
    ProductApiContract.ChatbotListResponse listChatbots(
            @PathVariable UUID projectId, HttpServletRequest request) {
        return service.listChatbots(projectId, trace(request));
    }

    @GetMapping("/chatbots/{chatbotId}")
    ProductApiContract.ChatbotResponse getChatbot(
            @PathVariable UUID chatbotId, HttpServletRequest request) {
        return service.getChatbot(chatbotId, trace(request));
    }

    @PostMapping("/chatbots/{chatbotId}/query")
    ProductApiContract.RagQueryResponse queryChatbot(
            @PathVariable UUID chatbotId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.RagQueryRequest body,
            HttpServletRequest request) {
        return service.query(chatbotId, trace(request), key, body);
    }

    @GetMapping("/agent-jobs/{jobId}")
    ProductApiContract.AgentJobResponse getJob(
            @PathVariable UUID jobId, HttpServletRequest request) {
        return service.getJob(jobId, trace(request));
    }

    @GetMapping("/agent-jobs")
    ProductApiContract.AgentJobListResponse listJobs(
            @RequestParam UUID projectId, HttpServletRequest request) {
        return service.listJobs(projectId, trace(request));
    }

    @PostMapping("/agent-jobs/{jobId}/cancel")
    ProductApiContract.AgentJobResponse cancelJob(
            @PathVariable UUID jobId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return service.cancelJob(jobId, trace(request), key, body);
    }

    @PostMapping("/agent-jobs/{jobId}/retry")
    ProductApiContract.AgentJobResponse retryJob(
            @PathVariable UUID jobId,
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody ProductApiContract.StateMutationRequest body,
            HttpServletRequest request) {
        return service.retryJob(jobId, trace(request), key, body);
    }

    private static UUID trace(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}
