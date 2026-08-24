package org.urizo.axmodulestudio.backend.knowledge.dto;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Compatibility facade for the existing Stage 3 product API payloads.
 *
 * <p>Its nested types remain stable for public contract compatibility. New CMS domains own
 * feature-local contract types instead of extending this facade.
 */
public final class ProductApiContract {

    public static final String SCHEMA_VERSION = "1.0";

    private ProductApiContract() {
    }

    public record CreateProjectRequest(
            @NotBlank String schemaVersion,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description) {
        public CreateProjectRequest { requireVersion(schemaVersion); }
    }

    public record CreateConnectorRequest(
            @NotBlank String schemaVersion,
            @NotBlank @Size(max = 120) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String name,
            @NotNull URI baseUrl,
            @NotBlank @Size(max = 500) @Pattern(regexp = "^/(?!/).*$") String endpoint,
            @NotBlank String method,
            @NotNull JsonNode authentication,
            @NotNull List<JsonNode> requestParameters,
            @NotNull JsonNode response,
            @NotNull JsonNode pagination,
            @NotNull JsonNode documentMapping) {
        public CreateConnectorRequest {
            requireVersion(schemaVersion);
            requestParameters = List.copyOf(requestParameters);
        }
    }

    public record ConnectorPreviewRequest(
            @NotBlank String schemaVersion,
            @Min(1) @Max(20) int maxItems,
            JsonNode parameters) {
        public ConnectorPreviewRequest { requireVersion(schemaVersion); }
    }

    public record ConnectorSyncRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID connectorVersionId) {
        public ConnectorSyncRequest { requireVersion(schemaVersion); }
    }

    public record CreateKnowledgeBaseRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID projectId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description) {
        public CreateKnowledgeBaseRequest { requireVersion(schemaVersion); }
    }

    public record StartKnowledgeBuildRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID connectorVersionId,
            @Size(max = 120) String label) {
        public StartKnowledgeBuildRequest { requireVersion(schemaVersion); }
    }

    public record StateMutationRequest(
            @NotBlank String schemaVersion,
            @Min(1) Integer expectedStateVersion) {
        public StateMutationRequest { requireVersion(schemaVersion); }
    }

    public record RollbackKnowledgeRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID targetKnowledgeVersionId) {
        public RollbackKnowledgeRequest { requireVersion(schemaVersion); }
    }

    public record CreateChatbotRequest(
            @NotBlank String schemaVersion,
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID knowledgeBaseId) {
        public CreateChatbotRequest { requireVersion(schemaVersion); }
    }

    public record RagQueryRequest(
            @NotBlank String schemaVersion,
            @NotBlank @Size(max = 4000) String query,
            UUID conversationId,
            @Min(1) @Max(20) Integer topK) {
        public RagQueryRequest { requireVersion(schemaVersion); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectResponse(
            String schemaVersion,
            UUID traceId,
            UUID projectId,
            String name,
            String description,
            String status,
            Instant createdAt) {
    }

    public record ProjectListResponse(
            String schemaVersion,
            UUID traceId,
            List<ProjectResponse> items) {
        public ProjectListResponse { items = List.copyOf(items); }
    }

    public record ConnectorResponse(
            String schemaVersion,
            UUID traceId,
            UUID projectId,
            UUID connectorId,
            UUID connectorVersionId,
            String name,
            String status,
            String configDigest,
            Instant createdAt) {
    }

    public record ConnectorListResponse(
            String schemaVersion,
            UUID traceId,
            List<ConnectorResponse> items) {
        public ConnectorListResponse { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewDocument(
            String documentId,
            String title,
            String content,
            List<String> category,
            URI sourceUrl,
            Instant sourceUpdatedAt) {
        public PreviewDocument { category = category == null ? List.of() : List.copyOf(category); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorPreviewResponse(
            String schemaVersion,
            UUID traceId,
            UUID connectorId,
            int itemCount,
            Integer totalCount,
            List<PreviewDocument> documents,
            boolean truncated,
            Instant checkedAt) {
        public ConnectorPreviewResponse { documents = List.copyOf(documents); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobAcceptedResponse(
            String schemaVersion,
            UUID traceId,
            UUID jobId,
            String jobType,
            String status,
            String statusUrl,
            Instant acceptedAt,
            UUID knowledgeVersionId,
            UUID connectorVersionId,
            String configDigest) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeBaseResponse(
            String schemaVersion,
            UUID traceId,
            UUID knowledgeBaseId,
            UUID projectId,
            String name,
            String description,
            @JsonInclude(JsonInclude.Include.ALWAYS) UUID activeVersionId,
            Instant createdAt) {
    }

    public record KnowledgeBaseListResponse(
            String schemaVersion,
            UUID traceId,
            List<KnowledgeBaseResponse> items) {
        public KnowledgeBaseListResponse { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeVersionResponse(
            String schemaVersion,
            UUID traceId,
            UUID knowledgeVersionId,
            UUID knowledgeBaseId,
            UUID connectorVersionId,
            UUID buildJobId,
            int versionNumber,
            String label,
            String status,
            String configDigest,
            int documentCount,
            int chunkCount,
            Double score,
            Instant createdAt,
            Instant readyAt,
            Instant activatedAt) {
    }

    public record KnowledgeVersionListResponse(
            String schemaVersion,
            UUID traceId,
            List<KnowledgeVersionResponse> items) {
        public KnowledgeVersionListResponse { items = List.copyOf(items); }
    }

    public record ChatbotResponse(
            String schemaVersion,
            UUID traceId,
            UUID chatbotId,
            UUID projectId,
            UUID knowledgeBaseId,
            String name,
            String status,
            Instant createdAt) {
    }

    public record ChatbotListResponse(
            String schemaVersion,
            UUID traceId,
            List<ChatbotResponse> items) {
        public ChatbotListResponse { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentJobResponse(
            String schemaVersion,
            UUID traceId,
            UUID jobId,
            UUID projectId,
            String jobType,
            String status,
            int stateVersion,
            @Valid JobProgress progress,
            List<ResourceRef> resourceRefs,
            JobFailure failure,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant finishedAt) {
        public AgentJobResponse { resourceRefs = List.copyOf(resourceRefs); }
    }

    public record AgentJobListResponse(
            String schemaVersion,
            UUID traceId,
            List<AgentJobResponse> items) {
        public AgentJobListResponse { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobProgress(
            String phase,
            int percent,
            Integer targetCount,
            Integer successCount,
            Integer failedCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceRef(String type, UUID id, String digest) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobFailure(
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RagQueryResponse(
            String schemaVersion,
            UUID traceId,
            UUID queryId,
            UUID conversationId,
            String outcome,
            String answer,
            List<Citation> citations,
            UUID knowledgeVersionId,
            Instant generatedAt) {
        public RagQueryResponse { citations = List.copyOf(citations); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(
            String documentId,
            String title,
            URI sourceUrl,
            String excerpt,
            double score) {
    }

    public record ProductSessionResponse(
            String schemaVersion,
            String accessToken,
            String tokenType,
            Instant expiresAt) {
    }

    public record ErrorEnvelope(String schemaVersion, UUID traceId, ErrorDetail error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
    }

    static void requireVersion(String version) {
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("schemaVersion must be 1.0.");
        }
    }
}
