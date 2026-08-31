package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.config.ProductRuntimeProperties;
import org.urizo.axmodulestudio.backend.knowledge.integration.EmbeddingClient;

class ProductJobRecoveryTest {

    @Test
    void productQueueAcceptsOnlyTheSingleJobIdField() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        UUID jobId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        assertThat(ProductJobWorker.queueJobId(
                objectMapper, "{\"jobId\":\"" + jobId + "\"}"))
                .isEqualTo(jobId);
        assertThat(ProductJobWorker.queueJobId(
                objectMapper,
                "{\"schemaVersion\":\"1.0\",\"jobId\":\"" + jobId + "\"}"))
                .isNull();
    }

    @Test
    void recoversOnlyOwnedRunningJobsAndFencesExhaustedAttempts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transactionStatus);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
        ProductBatchService service = new ProductBatchService(
                jdbc,
                new TransactionTemplate(manager),
                Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC),
                // 이 테스트는 Job 복구만 확인한다. 임베딩 경로는 호출되지 않는다.
                mock(EmbeddingClient.class));

        int recovered = service.recoverInterruptedJobs("spring-worker-1");

        assertThat(recovered).isEqualTo(5);
        ArgumentCaptor<String> exhaustedSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(
                exhaustedSql.capture(), eq(Integer.class), any(Object[].class));
        assertThat(exhaustedSql.getValue())
                .contains("status = 'FAILED'", "worker_id = ?", "attempt >= max_attempts",
                        "knowledge_version");
        ArgumentCaptor<String> requeueSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(requeueSql.capture(), any(Object[].class));
        assertThat(requeueSql.getValue())
                .contains("status = 'QUEUED'", "worker_id = ?", "attempt < max_attempts");
        verify(manager).commit(transactionStatus);
    }

    @Test
    void workerPerformsRecoveryOnlyBeforeItsFirstPoll() {
        ProductRuntimeProperties properties = mock(ProductRuntimeProperties.class);
        ProductBatchService batchService = mock(ProductBatchService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(properties.workerId()).thenReturn("spring-worker-1");
        when(properties.productQueue()).thenReturn("axms:product:jobs:v1");
        when(redis.opsForList()).thenReturn(lists);
        ProductJobWorker worker = new ProductJobWorker(
                redis,
                properties,
                batchService,
                mock(JobLauncher.class),
                mock(Job.class),
                mock(Job.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        worker.poll();
        worker.poll();

        verify(batchService, times(1)).recoverInterruptedJobs("spring-worker-1");
    }
}
