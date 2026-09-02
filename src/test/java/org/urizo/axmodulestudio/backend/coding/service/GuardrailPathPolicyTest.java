package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GuardrailPathPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "compose.dev.yaml",
            "compose.dev-build-trust.yaml",
            "compose.dev-live.yaml",
            "compose.preview.yaml",
            "Dockerfile",
            "src/main/docker/postgres/Dockerfile",
            "src/main/docker/nginx/default.conf",
            "src/main/resources/db/migration/V20260830073257815__create_queue.sql",
            "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java",
            "src/main/java/org/urizo/axmodulestudio/backend/coding/service/CodingToolService.java",
            "src/main/java/org/urizo/axmodulestudio/backend/orchestration/service/"
                    + "ProfileModelBindingService.java",
            "src/main/java/org/urizo/axmodulestudio/backend/knowledge/KnowledgeStore.java",
            "src/main/java/org/urizo/axmodulestudio/backend/integration/ai/gateway/"
                    + "ProviderChatAdapter.java"})
    @DisplayName("Backend paths that exist today and must stay closed to the model")
    void deniesRealBackendPaths(String path) {
        assertThat(GuardrailPathPolicy.isDenied(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "index.html",
            "vite.config.ts",
            "package.json",
            "pnpm-lock.yaml",
            "Dockerfile",
            "src/main.tsx",
            "src/features/auth/LoginPage.tsx",
            "src/features/coding/CodingJobPanel.tsx",
            "src/features/orchestration/ProfilePanel.tsx",
            "src/features/knowledge/KnowledgePanel.tsx"})
    @DisplayName("Frontend paths that exist today and must stay closed to the model")
    void deniesRealFrontendPaths(String path) {
        assertThat(GuardrailPathPolicy.isDenied(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/java/org/urizo/axmodulestudio/backend/cms/controller/MemberController.java",
            "src/main/java/org/urizo/axmodulestudio/backend/cms/service/BoardService.java",
            "src/test/java/org/urizo/axmodulestudio/backend/cms/service/BoardServiceTest.java",
            "src/main/resources/application.yaml",
            "src/features/cms/MemberListPage.tsx",
            "src/features/site/HomePage.tsx",
            "src/shared/ui/Button.tsx",
            "README.md"})
    @DisplayName("Work the demo actually asks for stays allowed")
    void allowsProductWork(String path) {
        assertThat(GuardrailPathPolicy.isDenied(path)).isFalse();
    }

    @Test
    @DisplayName("Member management keeps working while auth is closed, because it lives under cms")
    void allowsMemberManagementWhileAuthIsDenied() {
        String members =
                "src/main/java/org/urizo/axmodulestudio/backend/cms/controller/MemberController.java";
        String auth =
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java";

        assertThat(GuardrailPathPolicy.isDenied(members)).isFalse();
        assertThat(GuardrailPathPolicy.isDenied(auth)).isTrue();
    }

    @Test
    @DisplayName("A file name entry is denied wherever the file sits, so moving it does not help")
    void deniesFileNameEntriesAtAnyDepth() {
        assertThat(GuardrailPathPolicy.isDenied("package.json")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied("src/features/cms/package.json")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied("deep/nested/folder/.env.local")).isTrue();
    }

    @Test
    @DisplayName("A Windows-shaped or dot-prefixed path is judged the same as its Git form")
    void normalizesPathShape() {
        assertThat(GuardrailPathPolicy.isDenied("./compose.dev.yaml")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied(
                "src\\main\\resources\\db\\migration\\V1__init.sql")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied("/compose.dev.yaml")).isTrue();
    }

    @Test
    @DisplayName("A near miss on a denied folder name is not denied")
    void doesNotOverMatchSimilarNames() {
        assertThat(GuardrailPathPolicy.isDenied(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/authoring/Draft.java"))
                .isFalse();
        assertThat(GuardrailPathPolicy.isDenied("src/features/cms/authors/AuthorList.tsx"))
                .isFalse();
        assertThat(GuardrailPathPolicy.isDenied("docs/db/migration-notes.md")).isFalse();
    }

    @Test
    @DisplayName("A changed file list reports every denied entry, in order")
    void reportsEveryDeniedPathInOrder() {
        List<String> changedPaths = List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/service/BoardService.java",
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java",
                "src/main/resources/db/migration/V20260901__add_column.sql");

        assertThat(GuardrailPathPolicy.deniedPaths(changedPaths)).containsExactly(
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java",
                "src/main/resources/db/migration/V20260901__add_column.sql");
    }

    @Test
    @DisplayName("A clean change list reports nothing")
    void reportsNothingForAllowedWork() {
        List<String> changedPaths = List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/service/BoardService.java",
                "src/features/cms/MemberListPage.tsx");

        assertThat(GuardrailPathPolicy.deniedPaths(changedPaths)).isEmpty();
    }

    @Test
    @DisplayName("An empty or blank path is not treated as a match")
    void ignoresBlankPaths() {
        assertThat(GuardrailPathPolicy.isDenied("")).isFalse();
        assertThat(GuardrailPathPolicy.isDenied("   ")).isFalse();
    }

    @Test
    @DisplayName("The Backend scan the runner produces loses exactly the four closed packages")
    void hidesDeniedFoldersFromTheBackendScan() {
        String base = "src/main/java/org/urizo/axmodulestudio/backend/";
        List<String> scanned = List.of(
                base + "auth", base + "cms", base + "coding", base + "core",
                base + "health", base + "integration", base + "knowledge", base + "orchestration");

        assertThat(GuardrailPathPolicy.visibleFolders(scanned)).containsExactly(
                base + "cms", base + "core", base + "health", base + "integration");
    }

    @Test
    @DisplayName("The Frontend scan the runner produces loses exactly the four closed features")
    void hidesDeniedFoldersFromTheFrontendScan() {
        List<String> scanned = List.of(
                "src/features/auth", "src/features/cms", "src/features/coding",
                "src/features/knowledge", "src/features/ops", "src/features/orchestration",
                "src/features/site", "src/app", "src/shared/api", "src/shared/ui", "src/styles");

        assertThat(GuardrailPathPolicy.visibleFolders(scanned)).containsExactly(
                "src/features/cms", "src/features/ops", "src/features/site",
                "src/app", "src/shared/api", "src/shared/ui", "src/styles");
    }

    @Test
    @DisplayName("A trailing /** spans zero segments, so the bare folder is denied as well")
    void deniesTheFolderItselfNotOnlyItsFiles() {
        String auth = "src/main/java/org/urizo/axmodulestudio/backend/auth";

        assertThat(GuardrailPathPolicy.isDenied(auth)).isTrue();
        assertThat(GuardrailPathPolicy.isDenied(auth + "/security/SecurityConfig.java")).isTrue();
    }

    @Test
    @DisplayName("A folder that merely contains a denied folder stays offered")
    void keepsParentsOfDeniedFolders() {
        String integration = "src/main/java/org/urizo/axmodulestudio/backend/integration";

        assertThat(GuardrailPathPolicy.isDenied(integration)).isFalse();
        assertThat(GuardrailPathPolicy.isDenied(integration + "/ai")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied(integration + "/ai/gateway/Adapter.java")).isTrue();
    }

    @Test
    @DisplayName("A folder below a denied folder is denied too")
    void deniesFoldersBelowADeniedFolder() {
        assertThat(GuardrailPathPolicy.isDenied(
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied("src/main/resources/db/migration")).isTrue();
        assertThat(GuardrailPathPolicy.isDenied("src/main/docker/nginx")).isTrue();
    }

    @Test
    @DisplayName("A scan with nothing denied is returned unchanged")
    void keepsACleanScanIntact() {
        List<String> scanned = List.of("src/features/cms", "src/app", "src/styles");

        assertThat(GuardrailPathPolicy.visibleFolders(scanned)).isEqualTo(scanned);
    }
}
