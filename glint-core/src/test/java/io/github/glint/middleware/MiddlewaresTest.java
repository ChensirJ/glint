package io.github.glint.middleware;

import io.github.glint.Glint;
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
        assertEquals("1", c.headers().firstValue("retry-after").orElse(""));
        assertEquals(2, hits.get());
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

    private static HttpRequest get(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).GET().build();
    }
}