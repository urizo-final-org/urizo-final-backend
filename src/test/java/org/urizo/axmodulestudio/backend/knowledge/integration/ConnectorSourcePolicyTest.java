package org.urizo.axmodulestudio.backend.knowledge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

/**
 * 이 정책이 커넥터가 가리킬 수 있는 원천의 경계다. 목록을 넓히는 실수는 서버가 임의의 주소로
 * 요청을 보내게 만드는 문이 되므로, 막아야 하는 입력을 명시적으로 고정한다.
 */
class ConnectorSourcePolicyTest {

    private final ConnectorSourcePolicy policy =
            new ConnectorSourcePolicy("apis.data.go.kr, api.example.test");

    @Test
    void allowsTheFixtureAdapterSoTheExistingCorpusKeepsWorking() {
        assertThat(policy.allows("https://fixture.invalid/api")).isTrue();
        assertThat(policy.allows("https://tour.fixture.invalid")).isTrue();
        assertThat(policy.isFixture("https://fixture.invalid/api")).isTrue();
    }

    @Test
    void allowsConfiguredHostsRegardlessOfCase() {
        assertThat(policy.allows("https://apis.data.go.kr/1421000/bizinfo")).isTrue();
        assertThat(policy.allows("https://APIS.DATA.GO.KR/1421000/bizinfo")).isTrue();
        assertThat(policy.isFixture("https://apis.data.go.kr/1421000/bizinfo")).isFalse();
    }

    @Test
    void rejectsHostsThatOnlyLookLikeAnAllowedOne() {
        assertThat(policy.allows("https://apis.data.go.kr.attacker.test/x")).isFalse();
        assertThat(policy.allows("https://evil-apis.data.go.kr/x")).isFalse();
        // 하위 도메인도 별개 호스트다. 목록에 있는 이름과 정확히 같아야 한다.
        assertThat(policy.allows("https://sub.apis.data.go.kr/x")).isFalse();
    }

    @Test
    void rejectsPrivateAndLoopbackTargets() {
        for (String url : new String[] {
                "https://169.254.169.254/latest/meta-data",
                "https://localhost/api",
                "https://127.0.0.1/api",
                "https://10.0.0.5/api"}) {
            assertThat(policy.allows(url)).as("url %s", url).isFalse();
        }
    }

    @Test
    void rejectsNonHttpsAndCredentialBearingUrls() {
        assertThat(policy.allows("http://apis.data.go.kr/x")).isFalse();
        assertThat(policy.allows("https://user@apis.data.go.kr/x")).isFalse();
        assertThat(policy.allows("https://apis.data.go.kr:8443/x")).isFalse();
    }

    @Test
    void requireThrowsTheContractErrorRatherThanAGenericFailure() {
        assertThatThrownBy(() -> policy.require("https://elsewhere.test/x"))
                .isInstanceOf(ProductApiException.class)
                .hasMessageContaining("allowed list");
        policy.require("https://apis.data.go.kr/1421000/bizinfo");
    }

    @Test
    void anEmptyAllowListStillPermitsTheFixtureAdapterOnly() {
        ConnectorSourcePolicy locked = new ConnectorSourcePolicy("");

        assertThat(locked.allows("https://fixture.invalid/api")).isTrue();
        assertThat(locked.allows("https://apis.data.go.kr/x")).isFalse();
    }
}
