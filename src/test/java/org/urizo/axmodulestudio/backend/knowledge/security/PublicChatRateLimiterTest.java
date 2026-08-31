package org.urizo.axmodulestudio.backend.knowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

class PublicChatRateLimiterTest {

    private static final String CALLER = "203.0.113.7";

    @Test
    void allowsTheLimitAndRejectsTheNextCall() {
        MovableClock clock = new MovableClock(Instant.parse("2026-08-31T12:00:00Z"));
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(clock);

        for (int call = 0; call < PublicChatRateLimiter.LIMIT; call++) {
            int attempt = call;
            assertThatCode(() -> limiter.check(CALLER))
                    .as("call %d must pass", attempt + 1)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check(CALLER))
                .isInstanceOfSatisfying(ProductApiException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.retryAfterMs()).isEqualTo(PublicChatRateLimiter.WINDOW.toMillis());
                });
    }

    @Test
    void countsEachCallerSeparately() {
        MovableClock clock = new MovableClock(Instant.parse("2026-08-31T12:00:00Z"));
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(clock);
        for (int call = 0; call < PublicChatRateLimiter.LIMIT; call++) {
            limiter.check(CALLER);
        }

        assertThatCode(() -> limiter.check("198.51.100.4")).doesNotThrowAnyException();
    }

    @Test
    void prefersTheProxySuppliedRealIpOverTheSocketAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.9");
        request.addHeader("X-Real-IP", CALLER);

        assertThat(PublicChatRateLimiter.caller(request)).isEqualTo(CALLER);
    }

    @Test
    void fallsBackToTheSocketAddressWhenTheHeaderIsAbsentOrBlank() {
        MockHttpServletRequest absent = new MockHttpServletRequest();
        absent.setRemoteAddr("172.18.0.9");

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setRemoteAddr("172.18.0.9");
        blank.addHeader("X-Real-IP", "   ");

        assertThat(PublicChatRateLimiter.caller(absent)).isEqualTo("172.18.0.9");
        assertThat(PublicChatRateLimiter.caller(blank)).isEqualTo("172.18.0.9");
    }

    @Test
    void recoversAfterTheWindowRollsOver() {
        MovableClock clock = new MovableClock(Instant.parse("2026-08-31T12:00:00Z"));
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(clock);
        for (int call = 0; call < PublicChatRateLimiter.LIMIT; call++) {
            limiter.check(CALLER);
        }
        assertThatThrownBy(() -> limiter.check(CALLER)).isInstanceOf(ProductApiException.class);

        clock.advanceTo(Instant.parse("2026-08-31T12:01:00Z"));

        assertThatCode(() -> limiter.check(CALLER)).doesNotThrowAnyException();
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        private void advanceTo(Instant next) {
            this.now = next;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
