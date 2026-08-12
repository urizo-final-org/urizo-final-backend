package org.urizo.axmodulestudio.backend.dev.cms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LocalDevRequestGuardTest {

    private final LocalDevRequestGuard guard = new LocalDevRequestGuard("fixed-csrf-token");

    @Test
    void acceptsLoopbackOriginAndMatchingToken() {
        MockHttpServletRequest request = request("127.0.0.1", "http://127.0.0.1:5173", "fixed-csrf-token");

        assertThatCode(() -> guard.requireMutation(request)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRemoteAddressOriginAndTokenMismatch() {
        assertThatThrownBy(() -> guard.requireMutation(request(
                "192.0.2.10", "http://127.0.0.1:5173", "fixed-csrf-token")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.requireMutation(request(
                "127.0.0.1", "https://example.com", "fixed-csrf-token")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.requireMutation(request(
                "127.0.0.1", "http://localhost:5173", "wrong-token")))
                .isInstanceOf(SecurityException.class);
    }

    private static MockHttpServletRequest request(String remoteAddress, String origin, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("Origin", origin);
        request.addHeader(LocalDevRequestGuard.CSRF_HEADER, token);
        return request;
    }
}
