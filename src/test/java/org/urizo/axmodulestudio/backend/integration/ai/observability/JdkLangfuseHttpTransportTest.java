package org.urizo.axmodulestudio.backend.integration.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class JdkLangfuseHttpTransportTest {

    private HttpServer server;
    private JdkLangfuseHttpTransport transport;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        transport = new JdkLangfuseHttpTransport(Duration.ofSeconds(1));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void receivesUtf8BodyAtTheExactSizeLimit() throws Exception {
        byte[] bytes = "{\"data\":\"정상\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/normal", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });

        LangfuseHttpTransport.Response response = transport.get(
                endpoint("/normal"), Map.of(), Duration.ofSeconds(2), bytes.length);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void timesOutWhenHeadersArriveButTheBodyStops() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var body = exchange.getResponseBody()) {
                body.write('{');
                body.flush();
                bodyStarted.countDown();
                try {
                    releaseBody.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                body.write('}');
            }
        });

        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> transport.get(
                    endpoint("/slow"), Map.of(), Duration.ofMillis(500), 1024))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(bodyStarted.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(3));
        }
        finally {
            releaseBody.countDown();
        }
    }

    @Test
    void rejectsOversizedChunkedBodyBeforeWaitingForItsEnd() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[1025]);
                body.flush();
                try {
                    releaseBody.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            assertThatThrownBy(() -> transport.get(
                    endpoint("/oversized"), Map.of(), Duration.ofSeconds(2), 1024))
                    .isInstanceOf(IOException.class)
                    .isNotInstanceOf(HttpTimeoutException.class)
                    .hasMessageContaining("size limit");
        }
        finally {
            releaseBody.countDown();
        }
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
