package io.github.glint.http;

import io.github.glint.Glint;
import io.github.glint.middleware.Middlewares;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end HTTP roundtrips against an ephemeral Glint port.
 * Uses {@code Connection: close} so keep-alive sockets do not linger.
 */
class VtServerIntegrationTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            long t0 = System.nanoTime();
            glint.stop();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(ms < 5_000, "stop() took too long: " + ms + "ms");
        }
    }

    @Test
    void getPostHeadOptionsMultipartDiagnosticsAndOtlp() throws Exception {
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder()
                        .idleTimeoutMs(2_000)
                        .shutdownWaitMs(1_000)
                        .build())
                .before(Middlewares.cors())
                .get("/hello", (req, resp) -> resp.text("hi"))
                .post("/echo", (req, resp) -> resp.json("{\"echo\":\"" + req.bodyText() + "\"}"))
                .post("/upload", (req, resp) -> {
                    var form = req.multipart();
                    String title = form.field("title").orElse("");
                    resp.json("{\"title\":\"" + title + "\",\"parts\":" + form.size() + "}");
                })
                .put("/item", (req, resp) -> resp.status(201).json("{\"ok\":true}"))
                .delete("/item", (req, resp) -> resp.status(204).empty())
                .diagnostics("/_vt")
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> hello = client.send(
                req(port, "/hello").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hello.statusCode());
        assertEquals("hi", hello.body());

        HttpResponse<Void> head = client.send(
                req(port, "/hello").method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());

        HttpResponse<String> echo = client.send(
                req(port, "/echo")
                        .POST(HttpRequest.BodyPublishers.ofString("abc"))
                        .header("Content-Type", "text/plain")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, echo.statusCode());
        assertTrue(echo.body().contains("abc"));

        String boundary = "----TestBoundary";
        String mp = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "demo\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> upload = client.send(
                req(port, "/upload")
                        .POST(HttpRequest.BodyPublishers.ofString(mp))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, upload.statusCode());
        assertTrue(upload.body().contains("demo"));

        HttpResponse<String> put = client.send(
                req(port, "/item")
                        .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, put.statusCode());

        HttpResponse<Void> del = client.send(
                req(port, "/item").DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, del.statusCode());

        HttpResponse<String> options = client.send(
                req(port, "/hello").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, options.statusCode());

        HttpResponse<String> missing = client.send(
                req(port, "/nope").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());

        HttpResponse<String> metrics = client.send(
                req(port, "/_vt/api/metrics").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("paths"));

        HttpResponse<String> otlp = client.send(
                req(port, "/_vt/api/otlp/v1/metrics").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, otlp.statusCode());
        assertTrue(otlp.body().contains("resourceMetrics"));
        assertTrue(otlp.body().contains("glint.requests.total"));

        HttpResponse<String> dash = client.send(
                req(port, "/_vt/").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, dash.statusCode());
        assertTrue(dash.body().toLowerCase().contains("glint"));
    }

    private static HttpRequest.Builder req(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
    }
}
