package io.github.glint.middleware;

import io.github.glint.Glint;
import io.github.glint.obs.Trace;
import io.github.glint.http.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class MiddlewaresTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void corsOptionsAndAccessLogAndRateLimit() throws Exception {
        List<String> lines = new ArrayList<>();
        AtomicInteger hits = new AtomicInteger();
        long allowedBefore = Trace.METRICS.rateLimitAllowed();
        long rejectedBefore = Trace.METRICS.rateLimitRejected();

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .before(Middlewares.accessLog(lines::add))
                .before(Middlewares.cors(CorsOptions.builder()
                        .allowOrigin("https://example.com")
                        .exposeHeaders("X-Trace-Id")
                        .build()))
                .before(Middlewares.rateLimit(2, 2, req -> "user"))
                .before(Middlewares.exceptionHandler())
                .get("/ok", (req, resp) -> {
                    hits.incrementAndGet();
                    resp.header("X-Trace-Id", "t1").text("ok");
                })
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> options = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok"))
                        .timeout(Duration.ofSeconds(3))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, options.statusCode());
        assertEquals("https://example.com",
                options.headers().firstValue("access-control-allow-origin").orElse(""));
        assertTrue(options.headers().firstValue("access-control-expose-headers").orElse("")
                .contains("X-Trace-Id"));

        HttpResponse<String> a = client.send(get(port, "/ok"), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> b = client.send(get(port, "/ok"), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> c = client.send(get(port, "/ok"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, a.statusCode());
        assertEquals(200, b.statusCode());
        assertEquals(429, c.statusCode());
        assertFalse(c.headers().firstValue("retry-after").orElse("").isBlank());
        assertEquals(2, hits.get());
        assertEquals(2L, Trace.METRICS.rateLimitAllowed() - allowedBefore);
        assertEquals(1L, Trace.METRICS.rateLimitRejected() - rejectedBefore);
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(l -> l.contains("GET /ok")));
    }

    @Test
    void tokenBucketRefills() throws InterruptedException {
        TokenBucketRateLimiter lim = new TokenBucketRateLimiter(100, 1);
        assertTrue(lim.tryAcquire("k"));
        assertFalse(lim.tryAcquire("k"));
        Thread.sleep(30);
        assertTrue(lim.tryAcquire("k"));
    }


    @Test
    void gzipCompressionNegotiated() throws Exception {
        String payload = "x".repeat(2000);
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .before(Middlewares.compress())
                .get("/big", (req, resp) -> resp.text(payload))
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<byte[]> gz = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept-Encoding", "gzip")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, gz.statusCode());
        assertEquals("gzip", gz.headers().firstValue("content-encoding").orElse(""));
        assertTrue(gz.headers().firstValue("vary").orElse("").toLowerCase().contains("accept-encoding"));
        try (GZIPInputStream gin = new GZIPInputStream(new ByteArrayInputStream(gz.body()))) {
            String decoded = new String(gin.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(payload, decoded);
        }

        HttpResponse<String> plain = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept-Encoding", "identity")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, plain.statusCode());
        assertTrue(plain.headers().firstValue("content-encoding").isEmpty());
        assertEquals(payload, plain.body());
    }

    @Test
    void accessLogJsonIncludesStatusPathBytesAndRemote() throws Exception {
        List<String> lines = new ArrayList<>();
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .before(Middlewares.accessLogJson(lines::add))
                .get("/ping", (req, resp) -> resp.text("pong"))
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> r = client.send(get(port, "/ping"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("pong", r.body());
        assertFalse(lines.isEmpty());
        String json = lines.get(lines.size() - 1);
        assertTrue(json.contains("\"method\":\"GET\""), json);
        assertTrue(json.contains("\"path\":\"/ping\""), json);
        assertTrue(json.contains("\"status\":200"), json);
        assertTrue(json.contains("\"durationMs\":"), json);
        assertTrue(json.contains("\"bytes\":4"), json);
        assertTrue(json.contains("\"remote\":"), json);
        assertTrue(json.contains("\"ts\":"), json);
    }
    private static HttpRequest get(int port, String path) {

        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).GET().build();
    }
}