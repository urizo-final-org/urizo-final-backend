package org.urizo.axmodulestudio.backend.knowledge.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.config.ProductRuntimeProperties;

class TransactionalOutboxDispatcherTest {

    @Test
    void queueBoundaryPublishesOnlyTheJobIdentifier() {
        UUID jobId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        assertThat(TransactionalOutboxDispatcher.queuePayload(jobId))
                .isEqualTo("{\"jobId\":\"11111111-1111-4111-8111-111111111111\"}");
    }

    @Test
    void dispatchNormalizesLegacyOutboxRowsFromAggregateId() throws Exception {
        UUID outboxId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID jobId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transactionStatus);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        ProductRuntimeProperties properties = mock(ProductRuntimeProperties.class);
        when(properties.outboxLease()).thenReturn(Duration.ofSeconds(30));
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject(1, UUID.class)).thenReturn(outboxId);
        when(resultSet.getString(2)).thenReturn("axms:coding:jobs:v1");
        when(resultSet.getObject(3, UUID.class)).thenReturn(jobId);
        AtomicInteger queryCount = new AtomicInteger();
        when(jdbc.query(
                argThat(sql -> sql.contains("aggregate_id") && !sql.contains("payload")),
                any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    if (queryCount.getAndIncrement() > 0) {
                        return List.of();
                    }
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionalOutboxDispatcher dispatcher = new TransactionalOutboxDispatcher(
                jdbc,
                new TransactionTemplate(manager),
                redis,
                properties,
                Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC));

        dispatcher.dispatch();

        verify(lists).leftPush(
                "axms:coding:jobs:v1",
                "{\"jobId\":\"11111111-1111-4111-8111-111111111111\"}");
    }

    @Test
    void readinessPingAlwaysClosesItsDedicatedConnection() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        TransactionalOutboxDispatcher dispatcher = new TransactionalOutboxDispatcher(
                mock(JdbcTemplate.class), mock(TransactionTemplate.class), redis,
                mock(ProductRuntimeProperties.class), Clock.systemUTC());

        assertThat(dispatcher.queueReady()).isTrue();

        verify(connection).close();
    }
}
