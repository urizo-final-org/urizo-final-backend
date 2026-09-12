package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectorSecretResolverTest {

    @TempDir
    Path secrets;

    private ConnectorSecretResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(secrets.resolve("connector_sme_support_api"), "  service-key-value\n",
                StandardCharsets.UTF_8);
        // 커넥터 몫이 아닌 Secret. 커넥터 참조로는 절대 닿으면 안 된다.
        Files.writeString(secrets.resolve("cms_app_password"), "database-password",
                StandardCharsets.UTF_8);
        resolver = new ConnectorSecretResolver(secrets.toString());
    }

    @Test
    void resolvesReferenceToThePrefixedFileAndTrims() {
        assertThat(resolver.resolve("cms-secret://sme-support-api")).isEqualTo("service-key-value");
    }

    @Test
    void wellFormedAppliesTheSameNameRuleAtRegistrationTime() {
        assertThat(ConnectorSecretResolver.wellFormed("cms-secret://sme-support-api")).isTrue();
        // 아래는 전부 resolve()에서 죽는 참조다. 등록이 통과시키면 "등록 성공한 커넥터"가
        // 미리보기·수집에서야 터진다 — 그래서 같은 규칙을 등록 검증이 미리 적용한다.
        assertThat(ConnectorSecretResolver.wellFormed("cms-secret://data-go-kr/service-key")).isFalse();
        assertThat(ConnectorSecretResolver.wellFormed("cms-secret://Upper-Case")).isFalse();
        assertThat(ConnectorSecretResolver.wellFormed("cms-secret://")).isFalse();
        assertThat(ConnectorSecretResolver.wellFormed("fixture://public-data/local-v1")).isFalse();
        assertThat(ConnectorSecretResolver.wellFormed(null)).isFalse();
    }

    @Test
    void cannotReachSecretsOutsideTheConnectorPrefix() {
        // connector_ 접두사가 없으면 이 참조가 DB 비밀번호를 그대로 읽어낸다.
        assertThatThrownBy(() -> resolver.resolve("cms-secret://cms-app-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void rejectsPathTraversal() {
        for (String name : new String[] {"../cms_app_password", "..", "a/b", "./x", "a\\b"}) {
            assertThatThrownBy(() -> resolver.resolve("cms-secret://" + name))
                    .as("reference name %s", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid");
        }
    }

    @Test
    void rejectsAnUnknownScheme() {
        assertThatThrownBy(() -> resolver.resolve("fixture://sme-support-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cms-secret://");
    }

    @Test
    void rejectsAnEmptySecretRatherThanSendingABlankKey() throws IOException {
        Files.writeString(secrets.resolve("connector_blank"), "   \n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> resolver.resolve("cms-secret://blank"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void doesNotLeakTheSecretValueInFailureMessages() throws IOException {
        Files.writeString(secrets.resolve("connector_leaky"), "super-secret", StandardCharsets.UTF_8);

        assertThat(resolver.resolve("cms-secret://leaky")).isEqualTo("super-secret");
        assertThatThrownBy(() -> resolver.resolve("cms-secret://absent"))
                .hasMessageNotContaining("super-secret");
    }
}
