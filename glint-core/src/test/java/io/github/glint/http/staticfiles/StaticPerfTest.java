package io.github.glint.http.staticfiles;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import io.github.glint.obs.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Zero-copy, small-file cache, precompressed siblings, and immutable cache headers. */
class StaticPerfTest {

    @TempDir
    Path dir;

    private Glint app;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        app = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3000).shutdownWaitMs(1000).build())
                .staticFiles("/s", dir, StaticOptions.builder().cacheMaxEntryBytes(64 * 1024).revalidateMillis(0).build())
                .startAsync();
        base = "http://127.0.0.1:" + app.port() + "/s/";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<byte[]> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void largeFileIsSentZeroCopyAndIntact() throws Exception {
        byte[] data = new byte[1_500_000];
        new Random(42).nextBytes(data);
        Files.write(dir.resolve("big.bin"), data);

        long before = Trace.METRICS.zeroCopySends();
        HttpResponse<byte[]> r = get("big.bin");
        assertEquals(200, r.statusCode());
        assertEquals(String.valueOf(data.length), r.headers().firstValue("content-length").orElseThrow());
        assertArrayEquals(data, r.body());
        assertTrue(Trace.METRICS.zeroCopySends() > before, "expected a transferTo send");

        // Range on a large file uses the same file-region path.
        HttpResponse<byte[]> part = get("big.bin", "Range", "bytes=1000-1999");
        assertEquals(206, part.statusCode());
        assertArrayEquals(Arrays.copyOfRange(data, 1000, 2000), part.body());
        assertEquals("bytes 1000-1999/" + data.length, part.headers().firstValue("content-range").orElseThrow());

        // Keep-alive still works after a zero-copy body on the same connection.
        HttpResponse<byte[]> again = get("big.bin", "Range", "bytes=-10");
        assertArrayEquals(Arrays.copyOfRange(data, data.length - 10, data.length), again.body());

        // HEAD reports the length without a body.
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(URI.create(base + "big.bin"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(String.valueOf(data.length), head.headers().firstValue("content-length").orElseThrow());
    }

    @Test
    void smallFileCacheHitsAndInvalidatesOnChange() throws Exception {
        Path f = dir.resolve("note.txt");
        Files.writeString(f, "version-one");
        Files.setLastModifiedTime(f, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));

        long hits = Trace.METRICS.staticCacheHits();
        assertEquals("version-one", new String(get("note.txt").body(), StandardCharsets.UTF_8));
        assertEquals("version-one", new String(get("note.txt").body(), StandardCharsets.UTF_8));
        assertTrue(Trace.METRICS.staticCacheHits() > hits, "second read should be a cache hit");

        String oldEtag = get("note.txt").headers().firstValue("etag").orElseThrow();
        Files.writeString(f, "version-two!");
        Files.setLastModifiedTime(f, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        HttpResponse<byte[]> fresh = get("note.txt", "If-None-Match", oldEtag);
        assertEquals(200, fresh.statusCode(), "changed file must not be 304");
        assertEquals("version-two!", new String(fresh.body(), StandardCharsets.UTF_8));

        String newEtag = fresh.headers().firstValue("etag").orElseThrow();
        assertEquals(304, get("note.txt", "If-None-Match", newEtag).statusCode());

        // Range on a cached small file.
        HttpResponse<byte[]> part = get("note.txt", "Range", "bytes=0-6");
        assertEquals(206, part.statusCode());
        assertEquals("version", new String(part.body(), StandardCharsets.UTF_8));
    }

    @Test
    void precompressedGzipAndBrotliSiblings() throws Exception {
        String js = "console.log('hello glint');\n".repeat(200);
        Files.writeString(dir.resolve("app.js"), js);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(js.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzBytes = bos.toByteArray();
        Files.write(dir.resolve("app.js.gz"), gzBytes);

        HttpResponse<byte[]> plain = get("app.js");
        assertTrue(plain.headers().firstValue("content-encoding").isEmpty());
        assertEquals(js, new String(plain.body(), StandardCharsets.UTF_8));
        assertEquals("Accept-Encoding", plain.headers().firstValue("vary").orElseThrow());

        HttpResponse<byte[]> gz = get("app.js", "Accept-Encoding", "gzip, deflate");
        assertEquals("gzip", gz.headers().firstValue("content-encoding").orElseThrow());
        assertEquals(String.valueOf(gzBytes.length), gz.headers().firstValue("content-length").orElseThrow());
        assertEquals("Accept-Encoding", gz.headers().firstValue("vary").orElseThrow());
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz.body()))) {
            assertEquals(js, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String gzEtag = gz.headers().firstValue("etag").orElseThrow();
        assertNotEquals(plain.headers().firstValue("etag").orElseThrow(), gzEtag);
        assertTrue(gzEtag.contains("gzip"));
        assertEquals(304, get("app.js", "Accept-Encoding", "gzip", "If-None-Match", gzEtag).statusCode());

        // q=0 refuses gzip.
        assertTrue(get("app.js", "Accept-Encoding", "gzip;q=0").headers().firstValue("content-encoding").isEmpty());

        // Brotli is only ever served from an existing file, never produced at runtime.
        byte[] fakeBr = "pretend-brotli-bytes".getBytes(StandardCharsets.US_ASCII);
        Files.write(dir.resolve("app.js.br"), fakeBr);
        HttpResponse<byte[]> br = get("app.js", "Accept-Encoding", "gzip, br");
        assertEquals("br", br.headers().firstValue("content-encoding").orElseThrow());
        assertArrayEquals(fakeBr, br.body());

        // Range requests always get identity bytes.
        HttpResponse<byte[]> part = get("app.js", "Accept-Encoding", "gzip, br", "Range", "bytes=0-6");
        assertEquals(206, part.statusCode());
        assertTrue(part.headers().firstValue("content-encoding").isEmpty());
        assertEquals("console", new String(part.body(), StandardCharsets.UTF_8));
    }

    @Test
    void fingerprintedNamesGetImmutableCacheControl() throws Exception {
        Files.writeString(dir.resolve("app.3f2a9c1d.js"), "x");
        Files.writeString(dir.resolve("vendor-0123abcd.min.css"), "y");
        Files.writeString(dir.resolve("plain.js"), "z");
        assertEquals("public, max-age=31536000, immutable",
                get("app.3f2a9c1d.js").headers().firstValue("cache-control").orElseThrow());
        assertTrue(get("vendor-0123abcd.min.css").headers().firstValue("cache-control").orElseThrow().contains("immutable"));
        assertTrue(get("plain.js").headers().firstValue("cache-control").isEmpty());

        StaticOptions all = StaticOptions.builder().immutable(true).immutableMaxAgeSeconds(600).build();
        assertTrue(all.isImmutable("anything.txt"));
        assertEquals("public, max-age=600, immutable", all.immutableCacheControl());
    }

    @Test
    void acceptEncodingParsing() {
        assertTrue(StaticFiles.acceptsEncoding("gzip, br", "br"));
        assertTrue(StaticFiles.acceptsEncoding("*", "gzip"));
        assertFalse(StaticFiles.acceptsEncoding("br;q=0, *", "br"));
        assertFalse(StaticFiles.acceptsEncoding("", "gzip"));
        assertFalse(StaticFiles.acceptsEncoding("identity", "gzip"));
    }
}
