package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class JdkLangfuseHttpTransport implements LangfuseHttpTransport {

    private final HttpClient client;

    JdkLangfuseHttpTransport(Duration connectTimeout) {
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Response get(
            URI endpoint,
            Map<String, String> headers,
            Duration timeout,
            int maxResponseBytes) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .GET();
        headers.forEach(request::header);
        var pending = client.sendAsync(
                request.build(), ignored -> new BoundedBodySubscriber(maxResponseBytes));
        try {
            // The future completes only after the bounded body has been received.
            HttpResponse<byte[]> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new Response(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8));
        }
        catch (TimeoutException failure) {
            pending.cancel(true);
            throw new HttpTimeoutException("Langfuse response timed out.");
        }
        catch (InterruptedException failure) {
            pending.cancel(true);
            throw failure;
        }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException("Langfuse request failed.", failure.getCause());
        }
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private final int maximumBytes;
        private Flow.Subscription subscription;
        private int received;
        private boolean exceeded;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (exceeded) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximumBytes - received) {
                    exceeded = true;
                    subscription.cancel();
                    delegate.onError(new IOException(
                            "Langfuse response exceeded the configured size limit."));
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            if (!exceeded) {
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!exceeded) {
                delegate.onComplete();
            }
        }
    }
}
