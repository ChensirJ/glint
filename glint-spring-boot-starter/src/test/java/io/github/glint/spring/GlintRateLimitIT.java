package io.github.glint.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code glint.rate-limit.*} rejects bursts beyond the bucket with 429 while
 * leaving the {@code /_vt} diagnostics endpoints reachable.
 */
@SpringBootTest(
        classes = GlintRateLimitIT.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "glint.rate-limit.enabled=true",
                "glint.rate-limit.permits-per-second=0.01",
                "glint.rate-limit.burst=2"
        }
)
class GlintRateLimitIT {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void burstBeyondBucketGets429() throws Exception {
        int ok = 0;
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = get("/rl/ping");
            if (r.statusCode() == 200) {
                ok++;
            } else if (r.statusCode() == 429) {
                limited++;
                retryAfter = r.headers().firstValue("Retry-After").orElse(null);
            }
        }
        assertEquals(2, ok);
        assertEquals(3, limited);
        assertNotNull(retryAfter, "429 should carry Retry-After");
        assertEquals(200, get("/_vt/api/threads").statusCode(), "diagnostics must bypass the limiter");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootApplication
    static class App {
        @RestController
        static class PingController {
            @GetMapping("/rl/ping")
            String ping() {
                return "pong";
            }
        }
    }
}