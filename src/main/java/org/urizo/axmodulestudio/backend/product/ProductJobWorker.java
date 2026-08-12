package org.urizo.axmodulestudio.backend.product;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local-full")
final class ProductJobWorker {

    private final StringRedisTemplate redis;
    private final ProductRuntimeProperties properties;
    private final ProductBatchService batchService;
    private final JobLauncher jobLauncher;
    private final Job connectorSyncJob;
    private final Job knowledgeBuildJob;
    private final ObjectMapper objectMapper;
    private boolean recoveryComplete;

    ProductJobWorker(
            StringRedisTemplate productRedisTemplate,
            ProductRuntimeProperties properties,
            ProductBatchService batchService,
            JobLauncher jobLauncher,
            @Qualifier("connectorSyncBatchJob") Job connectorSyncJob,
            @Qualifier("knowledgeBuildBatchJob") Job knowledgeBuildJob,
            ObjectMapper objectMapper) {
        this.redis = productRedisTemplate;
        this.properties = properties;
        this.batchService = batchService;
        this.jobLauncher = jobLauncher;
        this.connectorSyncJob = connectorSyncJob;
        this.knowledgeBuildJob = knowledgeBuildJob;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${ax.product-runtime.worker-poll:300ms}")
    void poll() {
        if (!recoveryComplete) {
            batchService.recoverInterruptedJobs(properties.workerId());
            recoveryComplete = true;
        }
        UUID jobId = queuedJob();
        if (jobId == null || !batchService.claim(jobId, properties.workerId())) {
            return;
        }
        String jobType = batchService.jobType(jobId);
        try {
            Job job = "KNOWLEDGE_BUILD".equals(jobType) ? knowledgeBuildJob : connectorSyncJob;
            JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
                    .addString("productJobId", jobId.toString(), true)
                    .addString("dispatchId", UUID.randomUUID().toString(), true)
                    .toJobParameters());
            batchService.recordBatchExecution(jobId, execution.getId());
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                batchService.fail(jobId, "SERVICE_NOT_READY", true);
            }
        }
        catch (Exception failure) {
            batchService.fail(jobId, "SERVICE_NOT_READY", true);
        }
    }

    private UUID queuedJob() {
        try {
            String payload = redis.opsForList().rightPop(properties.productQueue());
            if (payload != null) {
                JsonNode event = objectMapper.readTree(payload);
                if ("1.0".equals(event.path("schemaVersion").asText())
                        && event.path("jobId").isTextual()) {
                    return UUID.fromString(event.path("jobId").asText());
                }
            }
        }
        catch (RedisConnectionFailureException | IllegalArgumentException failure) {
            return batchService.staleQueuedJob();
        }
        catch (Exception malformedEvent) {
            return batchService.staleQueuedJob();
        }
        return batchService.staleQueuedJob();
    }
}
