package io.github.glint.http.staticfiles;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StaticFilesTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void directoryMountServesMimeHeadAndRange(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("css");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("app.css"), "body{color:red}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("hello.txt"), "abcdefghij", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("index.html"), "<html>idx</html>", StandardCharsets.UTF_8);

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .staticFiles("/assets", dir)
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> css = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/assets/css/app.css"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertEquals("body{color:red}", css.body());
        assertEquals("bytes", css.headers().firstValue("accept-ranges").orElse(""));

        HttpResponse<Void> head = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/assets/hello.txt"))
                        .timeout(Duration.ofSeconds(3))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());
        assertEquals("10", head.headers().firstValue("content-length").orElse(""));

        HttpResponse<byte[]> ranged = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/assets/hello.txt"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Range", "bytes=0-3").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, ranged.statusCode());
        assertEquals("abcd", new String(ranged.body(), StandardCharsets.UTF_8));
        assertEquals("bytes 0-3/10", ranged.headers().firstValue("content-range").orElse(""));

        HttpResponse<String> index = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/assets/"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("idx"));
    }

    @Test
    void pathTraversalRejected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("secret.txt"), "nope", StandardCharsets.UTF_8);
        Path root = dir.resolve("public");
        Files.createDirectories(root);
        Files.writeString(root.resolve("ok.txt"), "ok", StandardCharsets.UTF_8);

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .staticFiles("/f", root)
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> attack = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/f/../secret.txt"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        // Either normalized away from mount (404 from router) or 403 from handler.
        assertTrue(attack.statusCode() == 403 || attack.statusCode() == 404, "status=" + attack.statusCode());

        assertTrue(StaticFiles.resolveUnderRoot(root, "../secret.txt").isEmpty());
        Optional<Path> ok = StaticFiles.resolveUnderRoot(root, "ok.txt");
        assertTrue(ok.isPresent());
        assertTrue(Files.isRegularFile(ok.get()));
    }

    @Test
    void classpathMount(@TempDir Path unused) throws Exception {
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .classpathFiles("/cp", "glint/static-test")
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cp/hello.txt"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals("classpath-hello", res.body().trim());
    }

    @Test
    void etagAndIfNoneMatchReturns304(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello-etag-body", StandardCharsets.UTF_8);

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .staticFiles("/s", dir)
                .startAsync();

        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> first = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/s/a.txt"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());
        String etag = first.headers().firstValue("etag").orElse("");
        assertFalse(etag.isBlank(), "ETag expected");
        assertTrue(first.headers().firstValue("last-modified").isPresent());

        HttpResponse<String> notMod = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/s/a.txt"))
                        .timeout(Duration.ofSeconds(3))
                        .header("If-None-Match", etag)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, notMod.statusCode());
        assertEquals(etag, notMod.headers().firstValue("etag").orElse(""));
        assertEquals("", notMod.body());

        String lm = first.headers().firstValue("last-modified").orElseThrow();
        HttpResponse<String> ims = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/s/a.txt"))
                        .timeout(Duration.ofSeconds(3))
                        .header("If-Modified-Since", lm)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, ims.statusCode());
    }

    @Test
    void parseRangeHelpers() {

        StaticFiles.Range r = StaticFiles.parseRange("bytes=2-5", 10);
        assertNotNull(r);
        assertTrue(r.satisfiable());
        assertEquals(2, r.start());
        assertEquals(5, r.end());

        StaticFiles.Range suffix = StaticFiles.parseRange("bytes=-3", 10);
        assertEquals(7, suffix.start());
        assertEquals(9, suffix.end());

        StaticFiles.Range bad = StaticFiles.parseRange("bytes=100-200", 10);
        assertFalse(bad.satisfiable());
    }
    @Test
    void directoryListingOffByDefault(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.txt"), "hi", StandardCharsets.UTF_8);
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .staticFiles("/files", dir)
                .startAsync();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + glint.port() + "/files/"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
    }

    @Test
    void directoryListingHtmlAndJson(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.txt"), "hi", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b&c.txt"), "x", StandardCharsets.UTF_8);
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .staticFiles("/files", dir, StaticOptions.builder().directoryListing(true).build())
                .startAsync();
        int port = glint.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        HttpResponse<String> html = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/files/"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept", "text/html")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, html.statusCode());
        assertTrue(html.headers().firstValue("content-type").orElse("").contains("text/html"));
        assertTrue(html.body().contains("a.txt"));
        assertTrue(html.body().contains("sub/"));
        assertTrue(html.body().contains("b&amp;c.txt"), html.body());

        HttpResponse<String> json = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/files/?format=json"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, json.statusCode());
        assertTrue(json.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(json.body().contains("\"name\":\"a.txt\""));
        assertTrue(json.body().contains("\"type\":\"dir\""));
        assertTrue(json.body().contains("\"name\":\"sub\""));
    }

    @Test
    void htmlEscapeEscapesMarkup() {
        assertEquals("a&amp;b&lt;c&gt;", StaticFiles.htmlEscape("a&b<c>"));
    }
}