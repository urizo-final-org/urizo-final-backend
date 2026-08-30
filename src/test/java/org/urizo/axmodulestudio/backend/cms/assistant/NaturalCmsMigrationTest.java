package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NaturalCmsMigrationTest {

    @Test
    void migrationCreatesOnlyTheCmsJobAndResultBoundary() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260830162029912__create_natural_cms_result_boundary.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.natural_cms_job")
                .contains("CREATE TABLE app.natural_cms_handler_result")
                .contains("preview_id UUID")
                .contains("structured_command JSONB")
                .doesNotContain("workspace_id")
                .doesNotContain("candidate_sha")
                .doesNotContain("diff_digest")
                .doesNotContain("pull_request");
    }
}
