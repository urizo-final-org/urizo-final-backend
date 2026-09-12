package org.urizo.axmodulestudio.backend.knowledge.integration;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

/**
 * 커넥터가 어떤 원천을 가리킬 수 있는지 정한다.
 *
 * <p>이전에는 {@code *.fixture.invalid}만 허용했다. 그 제한은 관리자가 등록한 주소로 서버가
 * 요청을 보내는 것을 막는 안전장치였지, 임시 코드가 아니다. 실제 도메인을 붙이려면 제한을
 * 없애는 것이 아니라 <b>더 좁은 경계로 바꿔야</b> 한다. 그래서 허용 호스트 목록을 둔다.
 *
 * <p>목록에 없는 호스트는 여전히 거부된다. 사설망 주소나 메타데이터 서비스로 요청을 유도하는
 * 입력이 들어와도 호스트가 목록에 없으면 등록·수집 어느 쪽도 통과하지 못한다.
 */
@Component
@Profile("local-full")
public class ConnectorSourcePolicy {

    private final Set<String> allowedHosts;

    ConnectorSourcePolicy(
            @Value("${ax.knowledge.connector.allowed-hosts:apis.data.go.kr}") String allowedHosts) {
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 결정적 픽스처 어댑터가 처리하는 원천인지. 1호(관광) 코퍼스가 이 경로를 쓴다. */
    public boolean isFixture(String baseUrl) {
        return DeterministicConnectorFixture.supports(baseUrl);
    }

    public boolean allows(String baseUrl) {
        if (isFixture(baseUrl)) {
            return true;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        }
        catch (IllegalArgumentException failure) {
            return false;
        }
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && uri.getUserInfo() == null
                && uri.getPort() == -1
                && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    public void require(String baseUrl) {
        if (!allows(baseUrl)) {
            throw new ProductApiException(
                    "CONNECTOR_SOURCE_NOT_ALLOWED",
                    "The connector source host is not on the allowed list.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
