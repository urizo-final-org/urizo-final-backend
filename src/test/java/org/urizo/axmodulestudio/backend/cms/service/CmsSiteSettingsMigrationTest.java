package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CmsSiteSettingsMigrationTest {

    @Test
    void migrationCreatesOnlyTheCmsOwnedSiteBoundary() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260831022313641__create_cms_site_settings.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.cms_site")
                .contains("template_key VARCHAR(40) NOT NULL REFERENCES app.cms_template")
                .contains("uq_cms_site_single_default")
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE ON app.cms_site TO cms_app")
                .doesNotContain("ai_profile_version")
                .doesNotContain("natural_cms_job");
    }
}
