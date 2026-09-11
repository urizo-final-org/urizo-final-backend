package org.urizo.axmodulestudio.backend.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SourceDocumentEventDateMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V20260909025316995__add_source_document_event_dates.sql");
    private static final Path SUPERSEDED_REVISION = Path.of(
            "src/main/resources/db/migration/"
                    + "V20260908152042464__add_source_document_event_dates.sql");

    @Test
    void migrationUsesTheReservedForwardRevisionAndKeepsDatesNullable() throws IOException {
        assertThat(MIGRATION).exists();
        assertThat(SUPERSEDED_REVISION).doesNotExist();

        assertThat(Files.readString(MIGRATION))
                .contains("ALTER TABLE app.source_document")
                .contains("ADD COLUMN event_start_date DATE")
                .contains("ADD COLUMN event_end_date DATE")
                .doesNotContain("NOT NULL")
                .doesNotContain("DEFAULT");
    }
}
