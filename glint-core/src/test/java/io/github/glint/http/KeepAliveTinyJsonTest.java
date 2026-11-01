package io.github.glint.http;

import io.github.glint.Glint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keep-alive tiny JSON must keep Content-Length framing and must not leak
 * headers or the previous body across requests on the same connection.
 */
class KeepAliveTinyJsonTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void pipelinedResponsesKeepContentLengthAndDoNotLeakHeaders() throws Exception {
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .get("/api/hello", (req, resp) -> {
                    String mark = req.header("x-mark").orElse("");
                    if ("1".equals(mark)) {
                        resp.header("X-One", "yes");
                        resp.cookie("a=1; Path=/");
                        resp.json("{\"message\":\"hello\"}");
                    } else {
                        resp.json("{\"message\":\"world-2\"}");
                    }
                })
                .startAsync();

        byte[] pipelined = (
                "GET /api/hello HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept-Encoding: identity\r\n"
                        + "X-Mark: 1\r\n\r\n"
                        + "GET /api/hello HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept-Encoding: identity\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", glint.port()), 3_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(pipelined);
            out.flush();

            SocketReader reader = new SocketReader(socket.getInputStream());
            HttpMessage first = reader.readMessage();
            HttpMessage second = reader.readMessage();

            assertEquals(200, first.status);
            assertEquals(19, first.contentLength);
            assertEquals("{\"message\":\"hello\"}", first.body);
            assertTrue(first.head.contains("X-One: yes"), first.head);
            assertTrue(first.head.contains("Set-Cookie: a=1; Path=/"), first.head);
            assertTrue(first.head.contains("Content-Length: 19"), first.head);
            assertTrue(first.head.contains("Connection: keep-alive"), first.head);

            assertEquals(200, second.status);
            assertEquals(21, second.contentLength);
            assertEquals("{\"message\":\"world-2\"}", second.body);
            assertFalse(second.head.contains("X-One"), second.head);
            assertFalse(second.head.contains("Set-Cookie"), second.head);
            assertTrue(second.head.contains("Content-Length: 21"), second.head);

            out.write(("GET /api/hello HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            HttpMessage third = reader.readMessage();
            assertEquals(200, third.status);
            assertEquals("{\"message\":\"world-2\"}", third.body);
            assertFalse(third.head.contains("X-One"), third.head);
        }
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
            int status = Integer.parseInt(head.substring(9, 12));
            int clAt = lower(head).indexOf("content-length:");
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
                pending = Arrays.copyOfRange(all, messageEnd, all.length);
            }
            String body = new String(all, headerEnd + 4, contentLength, StandardCharsets.UTF_8);
            return new HttpMessage(status, contentLength, head, body);
        }
    }

    private static String lower(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] += 32;
            }
        }
        return new String(chars);
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

    private record HttpMessage(int status, int contentLength, String head, String body) {
    }
}
