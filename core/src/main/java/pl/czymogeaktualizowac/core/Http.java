package pl.czymogeaktualizowac.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Small, polite HTTP client: identifying User-Agent, per-host minimum interval, retries with backoff.
 * Everything we call (WordPress.org APIs, forums, SVN, endoflife.date) is public and read-only.
 */
public final class Http {

    public static final String USER_AGENT =
            "czymogeaktualizowac/0.1 (+https://czymogeaktualizowac.pl; independent plugin release testing)";

    public record Response(int status, byte[] body, Map<String, String> headers) {
        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static class HttpException extends IOException {
        public final int status;

        public HttpException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final Map<String, Long> lastRequestByHost = new HashMap<>();
    private final long minIntervalMs;
    private final int retries;

    public Http() {
        this(400, 3);
    }

    public Http(long minIntervalMs, int retries) {
        this.minIntervalMs = minIntervalMs;
        this.retries = retries;
    }

    public String getString(String url) throws IOException {
        return get(url).text();
    }

    public byte[] getBytes(String url) throws IOException {
        return get(url).body();
    }

    public Response get(String url) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), url, Duration.ofSeconds(60));
    }

    /** WebDAV PROPFIND (used for the Subversion tags listing, which carries tag creation dates). */
    public Response propfind(String url, String xmlBody, int depth) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(xmlBody))
                .header("Depth", String.valueOf(depth))
                .header("Content-Type", "text/xml");
        return send(b, url, Duration.ofSeconds(60));
    }

    private Response send(HttpRequest.Builder builder, String url, Duration timeout) throws IOException {
        HttpRequest request = builder.timeout(timeout).header("User-Agent", USER_AGENT).build();
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            politeness(request.uri().getHost());
            try {
                HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int s = r.statusCode();
                if (s >= 200 && s < 300 || s == 207) {
                    Map<String, String> h = new HashMap<>();
                    r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
                    return new Response(s, r.body(), h);
                }
                last = new HttpException("HTTP " + s + " for " + url, s);
                if (s != 429 && s < 500) {
                    throw last; // 4xx other than 429: do not retry
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (HttpException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
            sleep(1000L * (attempt + 1));
        }
        throw last == null ? new IOException("request failed: " + url) : last;
    }

    private synchronized void politeness(String host) {
        long now = System.currentTimeMillis();
        long next = lastRequestByHost.getOrDefault(host, 0L) + minIntervalMs;
        if (next > now) {
            sleep(next - now);
        }
        lastRequestByHost.put(host, System.currentTimeMillis());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
