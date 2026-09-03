package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ProfileEditorLayoutMigrationTest {

    @Test
    void createsASeparateImmutableLayoutTableWithoutChangingProfileVersions() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260903023350023__create_ai_profile_editor_layout.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.ai_profile_editor_layout")
                .contains("profile_version_id UUID PRIMARY KEY")
                .contains("REFERENCES app.ai_profile_version (profile_version_id)")
                .contains("(layout_json - 'nodes') = '{}'::jsonb")
                .contains("BEFORE UPDATE OR DELETE ON app.ai_profile_editor_layout")
                .contains("GRANT SELECT, INSERT ON app.ai_profile_editor_layout TO ai_workspace")
                .doesNotContain(
                        "ALTER TABLE app.ai_profile_version",
                        "UPDATE app.ai_profile_version",
                        "snapshot_json",
                        "GRANT UPDATE",
                        "GRANT DELETE");
    }
}
