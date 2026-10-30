package io.github.glint.http;

import io.github.glint.Glint;
import io.github.glint.middleware.Middlewares;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end HTTP roundtrips against an ephemeral Glint port.
 */
class VtServerIT {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void getPostHeadOptionsAndDiagnostics() throws Exception {
        glint = Glint.create(0)
                .pinningWatcher(false)
                .before(Middlewares.cors())
                .get("/hello", (req, resp) -> resp.text("hi"))
                .post("/echo", (req, resp) -> resp.json("{\"echo\":\"" + req.bodyText() + "\"}"))
                .put("/item", (req, resp) -> resp.status(201).json("{\"ok\":true}"))
                .delete("/item", (req, resp) -> resp.status(204).empty())
                .diagnostics("/_vt")
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> hello = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hello.statusCode());
        assertEquals("hi", hello.body());

        HttpResponse<Void> head = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());
        assertTrue(head.headers().firstValue("content-length").isPresent());

        HttpResponse<String> echo = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
                        .POST(HttpRequest.BodyPublishers.ofString("abc"))
                        .header("Content-Type", "text/plain")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, echo.statusCode());
        assertTrue(echo.body().contains("abc"));

        HttpResponse<String> put = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/item"))
                        .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, put.statusCode());

        HttpResponse<Void> del = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/item")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, del.statusCode());

        HttpResponse<String> options = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, options.statusCode());
        assertTrue(options.headers().firstValue("access-control-allow-origin").isPresent());

        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());

        HttpResponse<String> badMethod = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello"))
                        .method("PATCH", HttpRequest.BodyPublishers.ofString("x"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, badMethod.statusCode());
        assertTrue(badMethod.headers().firstValue("allow").orElse("").contains("GET"));

        HttpResponse<String> metrics = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_vt/api/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("paths"));

        HttpResponse<String> dash = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_vt")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, dash.statusCode());
        assertTrue(dash.body().toLowerCase().contains("glint"));
    }
}
