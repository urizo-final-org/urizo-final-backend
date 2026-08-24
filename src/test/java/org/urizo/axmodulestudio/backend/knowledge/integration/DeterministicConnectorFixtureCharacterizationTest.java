package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class DeterministicConnectorFixtureCharacterizationTest {

    @Test
    void preservesTheLocalFixtureDocumentOrderAndBounds() {
        assertThat(DeterministicConnectorFixture.totalCount()).isEqualTo(3);
        assertThat(DeterministicConnectorFixture.documents(2))
                .satisfiesExactly(
                        first -> {
                            assertThat(first.documentId()).isEqualTo("local-tourism-001");
                            assertThat(first.title()).isEqualTo("서울 야간 관광 안내");
                            assertThat(first.sourceUrl()).isEqualTo(URI.create(
                                    "https://fixture.invalid/documents/local-tourism-001"));
                            assertThat(first.sourceUpdatedAt())
                                    .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
                        },
                        second -> assertThat(second.documentId()).isEqualTo("local-policy-002"));
    }

    @Test
    void preservesTheFixtureOnlyHttpsOriginPolicy() {
        assertThat(DeterministicConnectorFixture.supports("https://fixture.invalid")).isTrue();
        assertThat(DeterministicConnectorFixture.supports(
                "https://tourism.fixture.invalid/v1")).isTrue();
        assertThat(DeterministicConnectorFixture.supports("http://fixture.invalid")).isFalse();
        assertThat(DeterministicConnectorFixture.supports(
                "https://fixture.invalid.evil.example")).isFalse();
        assertThat(DeterministicConnectorFixture.supports(
                "https://user@fixture.invalid")).isFalse();
        assertThat(DeterministicConnectorFixture.supports(
                "https://fixture.invalid?token=value")).isFalse();
    }

    @Test
    void preservesDeterministicVectorAndGroundingBehavior() {
        String vector = DeterministicConnectorFixture.vector("서울 관광 안내");

        assertThat(vector).isEqualTo(DeterministicConnectorFixture.vector("서울 관광 안내"));
        assertThat(vector).startsWith("[").endsWith("]");
        assertThat(vector.split(",")).hasSize(32);
        assertThat(DeterministicConnectorFixture.hasGroundingOverlap(
                "서울 관광", "서울의 야간 관광 코스입니다.")).isTrue();
        assertThat(DeterministicConnectorFixture.hasGroundingOverlap(
                "창업 정책", "공공시설 안전 수칙입니다.")).isFalse();
    }
}
