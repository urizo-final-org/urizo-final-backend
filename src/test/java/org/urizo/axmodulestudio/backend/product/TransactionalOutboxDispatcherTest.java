package org.urizo.axmodulestudio.backend.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionalOutboxDispatcherTest {

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
