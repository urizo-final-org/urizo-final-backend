package org.urizo.axmodulestudio.backend.product;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local-full")
public final class ProductReadiness {

    static final String REQUIRED_MIGRATION_VERSION = "20260811220000";

    private final JdbcTemplate jdbc;
    private final TransactionalOutboxDispatcher outbox;

    ProductReadiness(JdbcTemplate productJdbcTemplate, TransactionalOutboxDispatcher outbox) {
        this.jdbc = productJdbcTemplate;
        this.outbox = outbox;
    }

    public Snapshot snapshot() {
        boolean database = databaseReady();
        boolean queue = outbox.queueReady();
        boolean migrations = database && migrationsReady();
        return new Snapshot(database && queue && migrations, List.of(
                new Check("core-database", database ? "UP" : "DOWN", true),
                new Check("queue", queue ? "UP" : "DOWN", true),
                new Check("migrations", migrations ? "UP" : "DOWN", true),
                new Check("coding-agent", "NOT_CONFIGURED", false)));
    }

    private boolean databaseReady() {
        try {
            return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        }
        catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean migrationsReady() {
        try {
            Boolean historyExists = jdbc.queryForObject(
                    "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(historyExists)) {
                return false;
            }
            Integer failed = jdbc.queryForObject(
                    "SELECT count(success) FROM public.flyway_schema_history WHERE NOT success",
                    Integer.class);
            String latest = jdbc.queryForObject(
                    "SELECT max(version) FROM public.flyway_schema_history WHERE success",
                    String.class);
            return failed != null && failed == 0 && latest != null
                    && latest.compareTo(REQUIRED_MIGRATION_VERSION) >= 0;
        }
        catch (RuntimeException failure) {
            return false;
        }
    }

    public record Snapshot(boolean ready, List<Check> checks) { }
    public record Check(String name, String status, boolean required) { }
}
