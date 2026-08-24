package org.urizo.axmodulestudio.backend.knowledge.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.urizo.axmodulestudio.backend.knowledge.queue.TransactionalOutboxDispatcher;

@ExtendWith(MockitoExtension.class)
class ProductReadinessTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private TransactionalOutboxDispatcher outbox;

    private ProductReadiness readiness;

    @BeforeEach
    void setUp() {
        readiness = new ProductReadiness(jdbc, outbox);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForObject(
                "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL", Boolean.class))
                .thenReturn(true);
        when(jdbc.queryForObject(
                "SELECT count(success) FROM public.flyway_schema_history WHERE NOT success", Integer.class))
                .thenReturn(0);
        when(outbox.queueReady()).thenReturn(true);
    }

    @Test
    void rejectsAHeadBeforeTheReadinessGrantMigration() {
        when(jdbc.queryForObject(
                "SELECT max(version) FROM public.flyway_schema_history WHERE success", String.class))
                .thenReturn("20260811214500");

        ProductReadiness.Snapshot snapshot = readiness.snapshot();

        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.checks())
                .filteredOn(check -> check.name().equals("migrations"))
                .extracting(ProductReadiness.Check::status)
                .containsExactly("DOWN");
    }

    @Test
    void acceptsTheCurrentMigrationHead() {
        when(jdbc.queryForObject(
                "SELECT max(version) FROM public.flyway_schema_history WHERE success", String.class))
                .thenReturn(ProductReadiness.REQUIRED_MIGRATION_VERSION);

        assertThat(readiness.snapshot().ready()).isTrue();
    }
}
