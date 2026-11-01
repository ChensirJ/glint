package io.github.glint.http;

import io.github.glint.Glint;
import io.github.glint.http.sse.SseEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A keep-alive hit must not rewrite the previous request object, and the
 * public header and query maps must reject mutation.
 */
class KeepAliveReuseIsolationTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void middlewareCannotPushHeaderOrQueryChangesIntoTheNextIdenticalRequest() throws Exception {
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .before((req, resp, next) -> {
                    assertThrows(UnsupportedOperationException.class,
                            () -> req.headers().put("x-injected", "from-middleware"));
                    assertThrows(UnsupportedOperationException.class,
                            () -> req.headers().remove("x-mark"));
                    assertThrows(UnsupportedOperationException.class,
                            () -> req.queryParams().put("z", "nope"));
                    assertThrows(UnsupportedOperationException.class,
                            () -> req.queryParams().remove("a"));
                    next.handle(req, resp);
                })
                .get("/q", (req, resp) -> resp.text(
                        req.header("x-mark").orElse("-") + ":" + req.queryParam("a").orElse("-")))
                .startAsync();

        String one = "GET /q?a=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Mark: kept\r\n\r\n";
        try (Socket socket = open(glint.port())) {
            OutputStream out = socket.getOutputStream();
            out.write((one + one).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            SocketReader reader = new SocketReader(socket.getInputStream());
            HttpMessage first = reader.readMessage();
            HttpMessage second = reader.readMessage();
            assertEquals("kept:1", first.body);
            assertEquals("kept:1", second.body);
            assertFalse(first.head.toLowerCase().contains("x-injected"), first.head);
        }
    }

    @Test
    void pathParamsStayOnTheirOwnRequestWhenTheNextHeaderBlockMatchesOrChanges() throws Exception {
        HttpRequest[] held = new HttpRequest[1];
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .get("/users/{id}", (req, resp) -> {
                    if (held[0] == null) {
                        held[0] = req;
                        req.pathParam("extra", "from-first");
                    }
                    String extra = req.pathParam("extra");
                    resp.text(req.pathParam("id") + ":" + (extra == null ? "none" : extra));
                })
                .startAsync();

        String u7 = "GET /users/7 HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n\r\n";
        String u8 = "GET /users/8 HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n\r\n";
        try (Socket socket = open(glint.port())) {
            OutputStream out = socket.getOutputStream();
            out.write((u7 + u7 + u8).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            SocketReader reader = new SocketReader(socket.getInputStream());
            assertEquals("7:from-first", reader.readMessage().body);
            assertEquals("7:none", reader.readMessage().body);
            assertEquals("8:none", reader.readMessage().body);
        }

        assertEquals("/users/7", held[0].path());
        assertEquals("7", held[0].pathParam("id"));
        assertEquals("from-first", held[0].pathParam("extra"));
        Map<String, String> frozen = held[0].pathParams();
        assertEquals("7", frozen.get("id"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("id", "changed"));
        assertEquals("7", held[0].pathParam("id"));
    }

    @Test
    void retainedRequestIsNotRewrittenByTheNextKeepAliveOrBySse() throws Exception {
        HttpRequest[] kept = new HttpRequest[1];
        HttpRequest[] sseHeld = new HttpRequest[1];
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .get("/hold", (req, resp) -> {
                    if (kept[0] == null) {
                        kept[0] = req;
                        req.pathParam("slot", "held");
                    }
                    resp.text(req.path() + ":" + String.valueOf(req.pathParam("slot")));
                })
                .get("/stream", (req, resp) -> {
                    sseHeld[0] = req;
                    req.pathParam("slot", "sse");
                    try (SseEmitter sse = SseEmitter.start(resp)) {
                        sse.send("hi");
                    }
                })
                .startAsync();

        String hold = "GET /hold HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n\r\n";
        String other = "GET /hold?x=2 HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n\r\n";
        try (Socket socket = open(glint.port())) {
            OutputStream out = socket.getOutputStream();
            out.write((hold + hold + other).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            SocketReader reader = new SocketReader(socket.getInputStream());
            assertEquals("/hold:held", reader.readMessage().body);
            assertEquals("/hold:null", reader.readMessage().body);
            assertEquals("/hold:null", reader.readMessage().body);
        }

        assertEquals("/hold", kept[0].path());
        assertEquals("", kept[0].query());
        assertEquals("held", kept[0].pathParam("slot"));
        assertEquals("127.0.0.1", kept[0].header("host").orElseThrow());

        try (Socket socket = open(glint.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String raw = readUntilClosed(socket.getInputStream());
            assertTrue(raw.contains("text/event-stream"), raw);
            assertTrue(raw.contains("data: hi"), raw);
        }

        assertEquals("/stream", sseHeld[0].path());
        assertEquals("sse", sseHeld[0].pathParam("slot"));
        assertEquals("127.0.0.1", sseHeld[0].header("host").orElseThrow());
        assertNotSame(kept[0], sseHeld[0]);
        assertEquals("/hold", kept[0].path());
        assertEquals("held", kept[0].pathParam("slot"));
        assertNull(kept[0].queryParam("x").orElse(null));
    }

    private static Socket open(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static String readUntilClosed(InputStream in) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) >= 0) {
            raw.write(buf, 0, n);
        }
        return raw.toString(StandardCharsets.US_ASCII);
    }

    private static final class SocketReader {
        private final InputStream in;
        private byte[] pending = new byte[0];

        private SocketReader(InputStream in) {
            this.in = in;
        }

        private HttpMessage readMessage() throws Exception {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            if (pending.length > 0) {
                raw.write(pending);
                pending = new byte[0];
            }
            byte[] buf = new byte[512];
            int headerEnd = indexOf(raw.toByteArray(), "\r\n\r\n");
            while (headerEnd < 0) {
                int r = in.read(buf);
                if (r < 0) {
                    break;
                }
                raw.write(buf, 0, r);
                headerEnd = indexOf(raw.toByteArray(), "\r\n\r\n");
            }
            byte[] all = raw.toByteArray();
            if (headerEnd < 0) {
                throw new AssertionError("no header end: " + raw.toString(StandardCharsets.US_ASCII));
            }
            String head = new String(all, 0, headerEnd, StandardCharsets.US_ASCII);
            int clAt = head.toLowerCase().indexOf("content-length:");
            int eol = head.indexOf('\r', clAt);
            int contentLength = Integer.parseInt(head.substring(clAt + "content-length:".length(), eol).trim());
            int messageEnd = headerEnd + 4 + contentLength;
            while (all.length < messageEnd) {
                int r = in.read(buf);
                if (r < 0) {
                    break;
                }
                raw.write(buf, 0, r);
                all = raw.toByteArray();
            }
            if (all.length > messageEnd) {
                pending = java.util.Arrays.copyOfRange(all, messageEnd, all.length);
            }
            String body = new String(all, headerEnd + 4, contentLength, StandardCharsets.UTF_8);
            return new HttpMessage(head, body);
        }
    }

    private static int indexOf(byte[] data, String needle) {
        byte[] p = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + p.length <= data.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (data[i + j] != p[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private record HttpMessage(String head, String body) {
    }
}
