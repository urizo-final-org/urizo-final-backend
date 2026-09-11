package org.urizo.axmodulestudio.backend.governance;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;

/** Called only by the direct CMS administrator endpoints, after successful domain work. */
@Service
@Profile("local-full")
public class CmsChangeRecorder {
    private static final Set<String> TYPES = Set.of("MENU", "CONTENT", "BOARD", "POST", "TEMPLATE");
    private static final Set<String> OPERATIONS = Set.of("CREATE", "UPDATE", "DELETE", "SAVE");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CmsChangeRecorder(@Qualifier("productJdbcTemplate") JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // The controller and CmsService share this JPA transaction and its product datasource.
    // An insert failure must roll back the resource change, not silently lose the history.
    @Transactional(transactionManager = "authJpaTransactionManager", propagation = Propagation.MANDATORY)
    public void record(AuthenticatedActor actor, String type, String id, String operation, String title) {
        Objects.requireNonNull(actor, "actor is required");
        if (!actor.role().isCmsAdministrator() || !TYPES.contains(type) || !OPERATIONS.contains(operation)
                || id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid direct CMS change record.");
        }
        jdbc.update("""
                INSERT INTO app.cms_change_history
                    (change_id, resource_type, resource_id, operation, title,
                     actor_id, actor_name, actor_role, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), type, id, operation, shortened(title, 240), actor.actorId(),
                shortened(actor.name(), 200), actor.role().name(), Timestamp.from(clock.instant()));
    }

    private static String shortened(String value, int max) {
        String text = value == null ? "" : value;
        return text.substring(0, Math.min(max, text.length()));
    }
}
