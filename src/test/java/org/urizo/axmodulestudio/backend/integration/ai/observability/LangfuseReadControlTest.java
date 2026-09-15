package org.urizo.axmodulestudio.backend.integration.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class LangfuseReadControlTest {
    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";
    private static final LangfuseHttpTransport.Response EMPTY = new LangfuseHttpTransport.Response(200, "{\"data\":[]}");

    @Test
    void metricsQuotaBlocksAllMetricQueriesButObservationsContinueAndRetryResumesAtDeadline() {
        var clock = new MutableClock();
        var metricCalls = new AtomicInteger();
        var observationCalls = new AtomicInteger();
        var service = service(clock, (endpoint, headers, timeout, maximum) -> {
            if (endpoint.getPath().endsWith("/metrics")) {
                if (metricCalls.incrementAndGet() == 1) return new LangfuseHttpTransport.Response(429, "private", "16153");
            } else observationCalls.incrementAndGet();
            return EMPTY;
        });
        assertThat(service.metrics(FROM, TO).status()).isEqualTo(LangfuseObservabilityService.Availability.UNAVAILABLE);
        clock.advance(16152);
        assertThat(service.tokenUsage(FROM, TO, null).status()).isEqualTo(LangfuseObservabilityService.Availability.UNAVAILABLE);
        service.metrics(FROM, "2026-09-03T00:00:00Z");
        assertThat(metricCalls).hasValue(1);
        assertThat(service.observations(FROM, TO).status()).isEqualTo(LangfuseObservabilityService.Availability.AVAILABLE);
        assertThat(observationCalls).hasValue(1);
        clock.advance(1);
        assertThat(service.tokenUsage(FROM, TO, null).status()).isEqualTo(LangfuseObservabilityService.Availability.AVAILABLE);
        assertThat(metricCalls).hasValue(2);
    }

    @Test
    void observationQuotaHonorsHttpDateAndDoesNotBlockMetrics() {
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        String retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock.instant().plusSeconds(90).atZone(ZoneOffset.UTC));
        var service = service(clock, (endpoint, headers, timeout, maximum) -> {
            if (endpoint.getPath().endsWith("/observations") && calls.incrementAndGet() == 1)
                return new LangfuseHttpTransport.Response(429, "", retryAfter);
            return EMPTY;
        });
        service.observations(FROM, TO);
        clock.advance(89);
        service.observations(FROM, "2026-09-03T00:00:00Z");
        assertThat(calls).hasValue(1);
        assertThat(service.metrics(FROM, TO).status()).isEqualTo(LangfuseObservabilityService.Availability.AVAILABLE);
        clock.advance(1);
        assertThat(service.observations(FROM, TO).status()).isEqualTo(LangfuseObservabilityService.Availability.AVAILABLE);
        assertThat(calls).hasValue(2);
    }

    @Test
    void absentInvalidOrPastRetryAfterUsesOneMinuteFallback() {
        for (String header : new String[] { null, "garbage", "-1", "0", "9999999999999999999999", "Tue, 1 Sep 2026 00:00:00 GMT" }) {
            var clock = new MutableClock();
            var calls = new AtomicInteger();
            var service = service(clock, (endpoint, headers, timeout, maximum) -> {
                calls.incrementAndGet();
                return new LangfuseHttpTransport.Response(429, "", header);
            });
            service.metrics(FROM, TO);
            clock.advance(59);
            service.tokenUsage(FROM, TO, null);
            assertThat(calls).hasValue(1);
            clock.advance(1);
            service.metrics(FROM, TO);
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void simultaneousIdenticalReadsShareCacheAndOtherReadBucketRemainsIndependent() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = service(new MutableClock(), (endpoint, headers, timeout, maximum) -> {
            if (endpoint.getPath().endsWith("/metrics")) {
                calls.incrementAndGet();
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
            }
            return EMPTY;
        });
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(() -> service.metrics(FROM, TO));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> service.metrics(FROM, TO));
            var observation = pool.submit(() -> service.observations(FROM, TO));
            assertThat(observation.get(2, TimeUnit.SECONDS).status()).isEqualTo(LangfuseObservabilityService.Availability.AVAILABLE);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(second.get(2, TimeUnit.SECONDS));
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void runningOccurrenceReusesBoundedSnapshotDespiteNewTimestampsAndKeepsAttemptsSeparate() {
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        var service = service(clock, (endpoint, headers, timeout, maximum) -> { calls.incrementAndGet(); return EMPTY; });
        Instant start = Instant.parse(FROM);
        var first = service.selectedObservations("job", "trace", "profile", 1, 1, "code", 1, "otel", start, start.plusSeconds(1));
        var next = service.selectedObservations("job", "trace", "profile", 1, 1, "code", 1, "otel", start, start.plusSeconds(2));
        assertThat(next).isSameAs(first);
        assertThat(calls).hasValue(1);
        service.selectedObservations("job", "trace", "profile", 1, 2, "code", 2, "otel-2", start, start.plusSeconds(3));
        assertThat(calls).hasValue(2);
        clock.advance(30);
        service.selectedObservations("job", "trace", "profile", 1, 1, "code", 1, "otel", start, start.plusSeconds(30));
        assertThat(calls).hasValue(3);
        service.selectedObservations("job", "trace", "profile", 1, 1, "code", 1, null, start, start.plusSeconds(30));
        assertThat(calls).hasValue(3);
    }

    private static LangfuseObservabilityService service(Clock clock, LangfuseHttpTransport transport) {
        var properties = new LangfuseProperties("https://jp.cloud.langfuse.com", "test-public", "test-secret",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30), 262_144);
        return new LangfuseObservabilityService(properties, transport, new ObjectMapper().findAndRegisterModules(), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-02T01:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
