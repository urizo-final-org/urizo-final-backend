package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ProfileDefaultTemplateMigrationTest {

    @Test
    void createsProfileScopedTemplatesFromTheCurrentProductionSnapshots() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260903145703043__create_ai_profile_default_template.sql"));

        assertThat(migration)
                .contains("CREATE TABLE app.ai_profile_default_template")
                .contains("'LLM_OPS'")
                .contains("'NATURAL_CMS'")
                .contains("\"id\":\"github_approval\"")
                .contains("\"id\":\"dev_merge_check\"")
                .contains("\"handlerKey\":\"cms.apply\"")
                .contains("GRANT SELECT, INSERT, UPDATE ON app.ai_profile_default_template")
                .doesNotContain("app.ai_profile_version (");
    }

    @Test
    void updatesOnlyDefaultTemplateModelBindingsToTheCompleteGeminiSelection() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V20260903234407711__update_default_template_model_bindings.sql"));

        assertThat(migration)
                .contains("UPDATE app.ai_profile_default_template")
                .contains("'LLM_OPS'")
                .contains("'NATURAL_CMS'")
                .contains("\"primary\": \"google-genai-gemini-3-6-flash\"")
                .contains("\"provider\": \"GOOGLE_GENAI\"")
                .contains("\"model\": \"gemini-3.6-flash\"")
                .contains("\"reasoningIntensity\": \"MEDIUM\"")
                .contains("IS DISTINCT FROM")
                .doesNotContain("app.ai_profile_version")
                .doesNotContain("handlerKey")
                .doesNotContain("\"nodes\"")
                .doesNotContain("\"edges\"");
        assertThat(count(migration, "\"primary\": \"google-genai-gemini-3-6-flash\""))
                .isEqualTo(5);
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
