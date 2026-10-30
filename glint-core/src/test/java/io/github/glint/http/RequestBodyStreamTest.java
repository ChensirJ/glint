package io.github.glint.http;

import io.github.glint.Glint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming request-body behaviour: large uploads must not be fully buffered
 * before the handler runs, and keep-alive must still work after a streamed read.
 */
class RequestBodyStreamTest {

    private static final char CR = 13;
    private static final char LF = 10;

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    private static String crlf(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(CR).append(LF);
        }
        return sb.toString();
    }

    @Test
    void parseLeavesBodyUnreadOnUnderlyingStream() throws Exception {
        byte[] payload = "STREAM-BODY-DATA".getBytes(StandardCharsets.UTF_8);
        String header = crlf(
                "POST /x HTTP/1.1",
                "Host: localhost",
                "Content-Length: " + payload.length,
                "");
        byte[] raw = new byte[header.getBytes(StandardCharsets.US_ASCII).length + payload.length];
        System.arraycopy(header.getBytes(StandardCharsets.US_ASCII), 0, raw, 0,
                header.getBytes(StandardCharsets.US_ASCII).length);
        System.arraycopy(payload, 0, raw, header.getBytes(StandardCharsets.US_ASCII).length, payload.length);

        CountingInputStream in = new CountingInputStream(new ByteArrayInputStream(raw));
        HttpRequest req = HttpParser.parse(in, ServerConfig.defaults());
        assertNotNull(req);
        // Parser must stop after headers; body bytes remain unread.
        assertEquals(header.getBytes(StandardCharsets.US_ASCII).length, in.bytesRead());
        assertEquals(payload.length, req.contentLength());

        byte[] got = req.bodyStream().readAllBytes();
        assertArrayEquals(payload, got);
        assertEquals(raw.length, in.bytesRead());
    }

    @Test
    void bodyBytesStillBuffersWhenRequested() throws Exception {
        String body = "{\"a\":1}";
        String raw = crlf(
                "POST /orders HTTP/1.1",
                "Host: localhost",
                "Content-Type: application/json",
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length,
                "") + body;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals(body, req.bodyText());
        // After buffering, stream still yields the same bytes.
        assertEquals(body, new String(req.bodyStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void bodyBytesAfterStreamReadThrows() throws Exception {
        String body = "abc";
        String raw = crlf(
                "POST /x HTTP/1.1",
                "Host: localhost",
                "Content-Length: 3",
                "") + body;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals('a', req.bodyStream().read());
        assertThrows(IllegalStateException.class, req::bodyBytes);
    }

    @Test
    void chunkedBodyStreamsAndEnforcesLimit() throws Exception {
        String raw = crlf(
                "POST /echo HTTP/1.1",
                "Host: localhost",
                "Transfer-Encoding: chunked",
                "")
                + "5" + CR + LF + "hello" + CR + LF
                + "1" + CR + LF + "!" + CR + LF
                + "0" + CR + LF + CR + LF;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals(-1, req.contentLength());
        assertEquals("hello!", new String(req.bodyStream().readAllBytes(), StandardCharsets.UTF_8));

        String big = crlf(
                "POST /echo HTTP/1.1",
                "Host: localhost",
                "Transfer-Encoding: chunked",
                "")
                + "5" + CR + LF + "hello" + CR + LF
                + "0" + CR + LF + CR + LF;
        ServerConfig tiny = ServerConfig.builder().maxRequestBodyBytes(4).build();
        HttpRequest over = HttpParser.parseString(big, tiny);
        HttpException ex = assertThrows(HttpException.class, () -> over.bodyStream().readAllBytes());
        assertEquals(413, ex.status());
    }

    @Test
    void largeUploadStreamsWithBoundedHandlerBuffer() throws Exception {
        final int size = 2 * 1024 * 1024; // 2 MiB
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder()
                        .maxRequestBodyBytes(4 * 1024 * 1024)
                        .idleTimeoutMs(5_000)
                        .shutdownWaitMs(1_000)
                        .build())
                .post("/upload", (req, resp) -> {
                    // Handler uses a small buffer; must not call bodyBytes().
                    CRC32 crc = new CRC32();
                    byte[] buf = new byte[8 * 1024];
                    long n = 0;
                    int r;
                    InputStream in = req.bodyStream();
                    while ((r = in.read(buf)) != -1) {
                        crc.update(buf, 0, r);
                        n += r;
                    }
                    resp.json("{\"bytes\":" + n + ",\"crc32\":" + crc.getValue() + "}");
                })
                .get("/ping", (req, resp) -> resp.text("pong"))
                .startAsync();

        int port = glint.port();
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i * 31);
        }
        CRC32 expect = new CRC32();
        expect.update(payload);

        // Heap before / after should not need a 2 MiB request array in the parser.
        // Functional check: CRC and length match, and a keep-alive GET follows.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String head = "POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n"
                    + "Content-Type: application/octet-stream\r\nContent-Length: " + size + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();

            String resp1 = readHttpMessage(in);
            assertTrue(resp1.contains("200"), resp1);
            assertTrue(resp1.contains("\"bytes\":" + size), resp1);
            assertTrue(resp1.contains("\"crc32\":" + expect.getValue()), resp1);

            out.write("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String resp2 = readHttpMessage(in);
            assertTrue(resp2.contains("200"), resp2);
            assertTrue(resp2.contains("pong"), resp2);
        }
    }

    @Test
    void drainUnreadBodyAllowsKeepAlive() throws Exception {
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .post("/ignore-body", (req, resp) -> resp.text("ok"))
                .get("/next", (req, resp) -> resp.text("next"))
                .startAsync();

        int port = glint.port();
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = 7;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            String head = "POST /ignore-body HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n"
                    + "Content-Length: " + payload.length + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            String resp1 = readHttpMessage(in);
            assertTrue(resp1.contains("ok"), resp1);

            out.write("GET /next HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String resp2 = readHttpMessage(in);
            assertTrue(resp2.contains("next"), resp2);
        }
    }

    private static String readHttpMessage(InputStream in) throws Exception {
        byte[] buf = new byte[16 * 1024];
        int n = 0;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (in.available() > 0 || n == 0) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
                String s = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                int sep = s.indexOf("\r\n\r\n");
                if (sep >= 0) {
                    int cl = 0;
                    for (String line : s.substring(0, sep).split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            cl = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    int bodyStart = sep + 4;
                    if (n - bodyStart >= cl) {
                        return s;
                    }
                }
            } else {
                Thread.sleep(5);
            }
        }
        return new String(buf, 0, n, StandardCharsets.ISO_8859_1);
    }

    /** Tracks how many bytes the parser has pulled from the underlying stream. */
    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private long bytesRead;

        CountingInputStream(InputStream in) {
            this.in = in;
        }

        long bytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b >= 0) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
            }
            return n;
        }
    }
}