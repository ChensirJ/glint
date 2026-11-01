package io.github.glint.examples.standalone;

import io.github.glint.Glint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Round-trip the standalone notes sample: JSON CRUD, path params, the home page,
 * SSE, {@code /_vt}, and {@link Glint#stop()}.
 */
class NotesServerTest {

    private Glint app;

    @AfterEach
    void stop() {
        if (app != null) {
            long started = System.nanoTime();
            app.stop();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs < 3_000L, "stop() took " + elapsedMs + "ms");
        }
    }

    @Test
    void crudFiltersPageStreamDiagnosticsAndShutdown() throws Exception {
        app = NotesServer.wire(0).pinningWatcher(false);
        app.startAsync();
        int port = app.port();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpResponse<String> home = exchange(client, port, "GET", "/", null);
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("Glint notes"));
        assertTrue(home.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        HttpResponse<String> health = exchange(client, port, "GET", "/health", null);
        assertEquals(200, health.statusCode());
        assertEquals("up", JsonBodies.readObject(health.body()).get("status"));

        HttpResponse<String> empty = exchange(client, port, "GET", "/api/notes", null);
        assertEquals(200, empty.statusCode());
        assertEquals(0, notes(empty.body()).size());

        HttpResponse<String> badJson = exchange(client, port, "POST", "/api/notes", "{");
        assertEquals(400, badJson.statusCode());

        HttpResponse<String> missingTitle = exchange(client, port, "POST", "/api/notes", "{}");
        assertEquals(400, missingTitle.statusCode());
        assertEquals(400L, JsonBodies.readObject(missingTitle.body()).get("status"));

        String createdJson = "{\"title\":\"say \\\"hi\\\"\",\"body\":\"oat\",\"done\":false}";
        HttpResponse<String> created = exchange(client, port, "POST", "/api/notes", createdJson);
        assertEquals(201, created.statusCode());
        assertEquals("/api/notes/1", created.headers().firstValue("Location").orElse(""));
        Map<String, Object> note = JsonBodies.readObject(created.body());
        assertEquals(1L, note.get("id"));
        assertEquals("say \"hi\"", note.get("title"));
        assertEquals("oat", note.get("body"));
        assertEquals(Boolean.FALSE, note.get("done"));

        HttpResponse<String> fetched = exchange(client, port, "GET", "/api/notes/1", null);
        assertEquals(200, fetched.statusCode());
        assertEquals("say \"hi\"", JsonBodies.readObject(fetched.body()).get("title"));

        HttpResponse<String> second = exchange(client, port, "POST", "/api/notes",
                "{\"title\":\"Ship sample\",\"done\":true}");
        assertEquals(201, second.statusCode());
        assertEquals("", JsonBodies.readObject(second.body()).get("body"));

        assertEquals(1, notes(exchange(client, port, "GET", "/api/notes?done=true", null).body()).size());
        assertEquals(1, notes(exchange(client, port, "GET", "/api/notes?done=false", null).body()).size());
        assertEquals(400, exchange(client, port, "GET", "/api/notes?done=maybe", null).statusCode());

        HttpResponse<String> replaced = exchange(client, port, "PUT", "/api/notes/1",
                "{\"title\":\"say \\\"hi\\\"\",\"body\":\"oat\",\"done\":true}");
        assertEquals(200, replaced.statusCode());
        assertEquals(Boolean.TRUE, JsonBodies.readObject(replaced.body()).get("done"));

        assertEquals(400, exchange(client, port, "GET", "/api/notes/nope", null).statusCode());
        assertEquals(400, exchange(client, port, "GET", "/api/notes/0", null).statusCode());
        assertEquals(404, exchange(client, port, "GET", "/api/notes/99", null).statusCode());

        HttpResponse<String> stream = exchange(client, port, "GET", "/api/notes/stream", null);
        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
        assertTrue(stream.body().contains("event: note"));
        assertTrue(stream.body().contains("event: end"));
        assertTrue(stream.body().contains("\"count\":2"));

        HttpResponse<String> deleted = exchange(client, port, "DELETE", "/api/notes/1", null);
        assertEquals(204, deleted.statusCode());
        assertEquals(404, exchange(client, port, "GET", "/api/notes/1", null).statusCode());

        HttpResponse<String> diagnostics = exchange(client, port, "GET", "/_vt", null);
        assertEquals(302, diagnostics.statusCode());
        assertTrue(diagnostics.headers().firstValue("Location").orElse("").endsWith("/_vt/"));
        HttpResponse<String> dashboard = exchange(client, port, "GET", "/_vt/", null);
        assertEquals(200, dashboard.statusCode());
        assertTrue(dashboard.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        long started = System.nanoTime();
        app.stop();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs < 3_000L, "stop() took " + elapsedMs + "ms");
        app = null;
        boolean refused = false;
        try {
            exchange(client, port, "GET", "/health", null);
        } catch (IOException closed) {
            refused = true;
        }
        assertTrue(refused, "listen socket still accepted a request");
    }

    @Test
    void jsonReaderAcceptsScalarsAndRejectsTrailingData() {
        Map<String, Object> body = JsonBodies.readObject(
                "{\"title\":\"say \\\"hi\\\"\",\"n\":1,\"ok\":true,\"tag\":\"\\u0041\"}");
        assertEquals("say \"hi\"", body.get("title"));
        assertEquals(1L, body.get("n"));
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals("A", body.get("tag"));
        try {
            JsonBodies.readObject("{\"a\":1} trailing");
            fail("expected trailing data to fail");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("Trailing"));
        }
        try {
            JsonBodies.readObject("[]");
            fail("expected a JSON object");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("object"));
        }
    }

    private static List<?> notes(String json) {
        Object value = JsonBodies.readObject(json).get("notes");
        assertInstanceOf(List.class, value);
        return (List<?>) value;
    }

    private static HttpResponse<String> exchange(HttpClient client, int port, String method, String path, String json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
