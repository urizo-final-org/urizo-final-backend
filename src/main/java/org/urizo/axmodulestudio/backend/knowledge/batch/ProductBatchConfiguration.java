package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.UUID;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.urizo.axmodulestudio.backend.knowledge.integration.DeterministicConnectorFixture;

@Configuration(proxyBeanMethods = false)
@Profile("local-full")
class ProductBatchConfiguration {

    @Bean
    Job connectorSyncBatchJob(
            JobRepository jobRepository,
            Step connectorSyncStep) {
        return new JobBuilder("connectorSyncBatchJob", jobRepository)
                .start(connectorSyncStep)
                .build();
    }

    @Bean
    Step connectorSyncStep(
            JobRepository jobRepository,
            PlatformTransactionManager productTransactionManager,
            ProductBatchService service) {
        return new StepBuilder("connectorSync", jobRepository)
                .tasklet((contribution, context) -> {
                    service.connectorSync(jobId(context.getStepContext().getJobParameters().get("productJobId")));
                    return RepeatStatus.FINISHED;
                }, productTransactionManager)
                .build();
    }

    @Bean
    Job knowledgeBuildBatchJob(
            JobRepository jobRepository,
            Step productCollectStep,
            Step productNormalizeStep,
            Step productChunkStep,
            Step productEmbedStep,
            Step productIndexStep,
            Step productEvaluateStep) {
        return new JobBuilder("knowledgeBuildBatchJob", jobRepository)
                .start(productCollectStep)
                .next(productNormalizeStep)
                .next(productChunkStep)
                .next(productEmbedStep)
                .next(productIndexStep)
                .next(productEvaluateStep)
                .build();
    }

    @Bean
    Step productCollectStep(JobRepository repository, PlatformTransactionManager manager,
            ProductBatchService service) {
        return phaseStep("productCollect", "COLLECT", repository, manager, service);
    }

    @Bean
    Step productNormalizeStep(JobRepository repository, PlatformTransactionManager manager,
            ProductBatchService service) {
        return phaseStep("productNormalize", "NORMALIZE", repository, manager, service);
    }

    @Bean
    Step productChunkStep(JobRepository repository, PlatformTransactionManager manager,
            ProductBatchService service) {
        return phaseStep("productChunk", "CHUNK", repository, manager, service);
    }

    @Bean
    Step productEmbedStep(JobRepository repository, PlatformTransactionManager manager,
            ProductBatchService service) {
        return phaseStep("productEmbed", "EMBED", repository, manager, service);
    }

    @Bean
    Step productIndexStep(JobRepository repository, PlatformTransactionManager manager,
            ProductBatchService service) {
        return phaseStep("productIndex", "INDEX", repository, manager, service);
    }

    @Bean
    Step productEvaluateStep(JobRepository repository, PlatformTransactionManager manager,
            ProductBatchService service) {
        return phaseStep("productEvaluate", "EVALUATE", repository, manager, service);
    }

    private static Step phaseStep(
            String name,
            String phase,
            JobRepository repository,
            PlatformTransactionManager manager,
            ProductBatchService service) {
        return new StepBuilder(name, repository)
                .tasklet((contribution, context) -> {
                    service.phase(
                            jobId(context.getStepContext().getJobParameters().get("productJobId")),
                            phase);
                    return RepeatStatus.FINISHED;
                }, manager)
                .build();
    }

    private static UUID jobId(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("productJobId parameter is required.");
        }
        return UUID.fromString(String.valueOf(value));
    }
}
