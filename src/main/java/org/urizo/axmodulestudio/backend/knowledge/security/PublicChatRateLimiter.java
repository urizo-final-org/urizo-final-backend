package org.urizo.axmodulestudio.backend.knowledge.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;

/**
 * 공개 질의 경로 전용 호출 한도. 같은 호출자 기준 60초당 30회.
 *
 * <p>질의 1건은 검색 1회를 유발하므로 0.5 rps면 시연에 충분하고 반복 호출은 막는다.
 * 윈도우가 넘어가면 Map을 통째로 교체한다. 만료 스캔이나 정리 스케줄러가 없어
 * 항목이 쌓이지 않는다.
 */
@Component
@Profile("local-full")
public final class PublicChatRateLimiter {

    static final Duration WINDOW = Duration.ofSeconds(60);
    static final int LIMIT = 30;

    private final Clock clock;
    private final AtomicReference<Window> window;

    PublicChatRateLimiter(Clock clock) {
        this.clock = clock;
        this.window = new AtomicReference<>(new Window(currentIndex(clock), new ConcurrentHashMap<>()));
    }

    /** 한 건을 계상하고 한도를 넘으면 429로 끊는다. */
    public void check(HttpServletRequest request) {
        check(caller(request));
    }

    /**
     * 호출자 식별. nginx가 {@code X-Real-IP}를 실제 peer 주소로 덮어쓰므로 그 값을
     * 우선 쓰고, 없으면 소켓 주소로 되돌아간다.
     *
     * <p>{@code X-Forwarded-For}는 쓰지 않는다. nginx의 {@code $proxy_add_x_forwarded_for}는
     * 클라이언트가 보낸 값 뒤에 실제 주소를 덧붙이므로 앞부분을 위조할 수 있다.
     *
     * <p>ponytail: 전역 {@code forward-headers-strategy}를 켜지 않는다. 그러면 인증과
     * 로깅까지 헤더를 신뢰하게 된다. 헤더 신뢰 범위를 한도 계산 안으로만 묶는다.
     * 이 신뢰는 spring-app 포트를 발행하지 않아 nginx 우회 호출이 불가능하다는
     * 전제 위에 있다 — 포트를 열거나 프록시를 바꾸면 이 전제부터 다시 본다.
     */
    static String caller(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp.trim();
    }

    void check(String caller) {
        long index = currentIndex(clock);
        Window current = window.get();
        if (current.index() != index) {
            window.compareAndSet(current, new Window(index, new ConcurrentHashMap<>()));
            current = window.get();
        }
        int used = current.counts()
                .computeIfAbsent(caller == null ? "unknown" : caller, key -> new AtomicInteger())
                .incrementAndGet();
        if (used > LIMIT) {
            throw new ProductApiException(
                    "RATE_LIMITED",
                    "Too many public chat requests from this client.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    true,
                    WINDOW.toMillis());
        }
    }

    private static long currentIndex(Clock clock) {
        return clock.instant().toEpochMilli() / WINDOW.toMillis();
    }

    private record Window(long index, Map<String, AtomicInteger> counts) {
    }
}
