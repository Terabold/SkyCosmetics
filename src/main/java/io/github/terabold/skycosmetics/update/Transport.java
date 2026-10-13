package io.github.terabold.skycosmetics.update;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTP GET, without following redirects: {@link Fetch} follows them itself, so it can check every host. Tests
 * put a fake in its place ({@link Updater#transport}); nothing else in the update code touches the network.
 */
public interface Transport {
    /** The response; its body must be closed. Never throws for an HTTP error status, only when there is no answer. */
    Response get(URI uri, Map<String, String> headers, Duration timeout) throws IOException;

    /** @param headers names in lower case */
    record Response(int status, Map<String, String> headers, InputStream body) implements Closeable {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public void close() throws IOException {
            if (body != null) body.close();
        }
    }

    /** The real one: Java's HTTP client, an 8 second connect timeout, HTTP/1.1 or 2 as the server likes. */
    final class Http implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

        @Override
        public Response get(URI uri, Map<String, String> headers, Duration timeout) throws IOException {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).GET();
            headers.forEach(b::header);
            try {
                HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                Map<String, String> h = new HashMap<>();
                for (Map.Entry<String, List<String>> e : r.headers().map().entrySet()) {
                    if (!e.getValue().isEmpty()) h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getFirst());
                }
                return new Response(r.statusCode(), h, r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
