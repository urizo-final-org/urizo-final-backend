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

    @Test
    void truncatesSuffixesDownToTwoCharactersWhenTheFullTokenDoesNotMatch() {
        // 조사가 붙은 토큰은 어간까지 깎아 매칭한다 ("반도식당의" -> "반도식당").
        assertThat(DeterministicConnectorFixture.hasGroundingOverlap(
                "반도식당의 대표메뉴는", "반도식당 정식이 인기입니다.")).isTrue();
        // 하한은 2글자다. 1글자까지 내려가 매칭하지 않는다.
        assertThat(DeterministicConnectorFixture.hasGroundingOverlap(
                "치킨집은", "치과 안내입니다.")).isFalse();
        // 원형이 이미 매칭되면 절단 없이 통과한다.
        assertThat(DeterministicConnectorFixture.hasGroundingOverlap(
                "전주 한옥", "전주 한옥마을 근처입니다.")).isTrue();
    }
}
