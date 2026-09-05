package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LocalProviderSecretMigrationTest {

    @Test
    void credentialDeleteAlsoDeletesItsProviderConnectionAudit() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260831195834460__cascade_provider_audit_on_credential_delete.sql"));

        assertThat(migration)
                .contains(
                        "DROP CONSTRAINT fk_local_provider_connection_audit_provider",
                        "ADD CONSTRAINT fk_local_provider_connection_audit_provider",
                        "REFERENCES app.local_provider_secret(provider)",
                        "ON DELETE CASCADE")
                .doesNotContain("DROP TABLE", "TRUNCATE", "GRANT ");
    }
}
