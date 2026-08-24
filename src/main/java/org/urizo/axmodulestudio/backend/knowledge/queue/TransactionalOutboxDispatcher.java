package org.urizo.axmodulestudio.backend.knowledge.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.config.ProductRuntimeProperties;

@Component
@Profile("local-full")
public final class TransactionalOutboxDispatcher {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final ProductRuntimeProperties properties;
    private final Clock clock;

    TransactionalOutboxDispatcher(
            JdbcTemplate productJdbcTemplate,
            TransactionTemplate productTransactionTemplate,
            StringRedisTemplate productRedisTemplate,
            ProductRuntimeProperties properties,
            Clock clock) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
        this.redis = productRedisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ax.product-runtime.outbox-poll:250ms}")
    void dispatch() {
        for (int index = 0; index < 20; index++) {
            Optional<ClaimedOutbox> claimed = claim();
            if (claimed.isEmpty()) {
                return;
            }
            ClaimedOutbox outbox = claimed.get();
            try {
                redis.opsForList().leftPush(outbox.destination(), outbox.payload());
                markPublished(outbox);
            }
            catch (RedisConnectionFailureException | IllegalStateException failure) {
                markFailed(outbox, "VALKEY_UNAVAILABLE");
                return;
            }
        }
    }

    public boolean queueReady() {
        try {
            RedisConnectionFactory connectionFactory = redis.getConnectionFactory();
            if (connectionFactory == null) {
                return false;
            }
            try (RedisConnection connection = connectionFactory.getConnection()) {
                return "PONG".equalsIgnoreCase(connection.ping());
            }
        }
        catch (RuntimeException failure) {
            return false;
        }
    }

    private Optional<ClaimedOutbox> claim() {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);
            List<ClaimedOutbox> rows = jdbc.query(
                    "SELECT outbox_id, destination, payload::text FROM app.transactional_outbox "
                            + "WHERE available_at <= ? AND (status IN ('PENDING', 'FAILED') "
                            + "OR (status = 'PUBLISHING' AND lease_expires_at < ?)) "
                            + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                    (rs, row) -> new ClaimedOutbox(
                            rs.getObject(1, UUID.class), UUID.randomUUID(),
                            rs.getString(2), rs.getString(3)),
                    Timestamp.from(now), Timestamp.from(now));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            ClaimedOutbox row = rows.get(0);
            jdbc.update("UPDATE app.transactional_outbox SET status = 'PUBLISHING', lease_id = ?, "
                            + "lease_expires_at = ?, publish_attempts = publish_attempts + 1, "
                            + "last_error_code = NULL, updated_at = ? WHERE outbox_id = ?",
                    row.leaseId(), Timestamp.from(now.plus(properties.outboxLease())),
                    Timestamp.from(now), row.outboxId());
            return Optional.of(row);
        });
    }

    private void markPublished(ClaimedOutbox outbox) {
        Instant now = Instant.now(clock);
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE app.transactional_outbox SET status = 'PUBLISHED', published_at = ?, "
                        + "lease_id = NULL, lease_expires_at = NULL, updated_at = ? "
                        + "WHERE outbox_id = ? AND status = 'PUBLISHING' AND lease_id = ?",
                Timestamp.from(now), Timestamp.from(now), outbox.outboxId(), outbox.leaseId()));
    }

    private void markFailed(ClaimedOutbox outbox, String code) {
        Instant now = Instant.now(clock);
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE app.transactional_outbox SET status = 'FAILED', available_at = ?, "
                        + "lease_id = NULL, lease_expires_at = NULL, last_error_code = ?, updated_at = ? "
                        + "WHERE outbox_id = ? AND status = 'PUBLISHING' AND lease_id = ?",
                Timestamp.from(now.plusSeconds(1)), code, Timestamp.from(now),
                outbox.outboxId(), outbox.leaseId()));
    }

    private record ClaimedOutbox(
            UUID outboxId, UUID leaseId, String destination, String payload) { }
}
