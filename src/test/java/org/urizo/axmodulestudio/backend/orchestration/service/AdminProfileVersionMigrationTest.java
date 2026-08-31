package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AdminProfileVersionMigrationTest {

    @Test
    void migrationAddsOnlyRequiredWritePrivilegesAndKeepsDeleteProtected() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260831011109932__grant_profile_version_admin_write.sql"));
        String originalContract = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260830013135942__create_ai_profile_version_read_contract.sql"));

        assertThat(migration)
                .contains("GRANT INSERT, UPDATE ON app.ai_profile_version TO ai_workspace;")
                .doesNotContain("GRANT DELETE", "ALTER TABLE", "DROP ");
        assertThat(originalContract)
                .contains("BEFORE UPDATE OR DELETE ON app.ai_profile_version")
                .contains("AI Profile Versions cannot be deleted")
                .contains("AI Profile Version identity and payload are immutable");
    }
}
