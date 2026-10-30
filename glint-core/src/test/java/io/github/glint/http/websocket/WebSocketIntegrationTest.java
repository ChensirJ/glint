package io.github.glint.http.websocket;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Raw-socket WebSocket upgrade + text echo against Glint.
 */
class WebSocketIntegrationTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void upgradeAndEchoText() throws Exception {
        AtomicReference<String> opened = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(5_000).shutdownWaitMs(1_000).build())
                .websocket("/ws/echo", new WebSocketHandler() {
                    @Override
                    public void onOpen(WebSocketSession session) {
                        opened.set(session.path());
                    }

                    @Override
                    public void onMessage(WebSocketSession session, String text) throws Exception {
                        session.sendText("echo:" + text);
                    }

                    @Override
                    public void onClose(WebSocketSession session) {
                        closed.countDown();
                    }
                })
                .startAsync();

        int port = glint.port();
        String key = Base64.getEncoder().encodeToString("glint-test-key-12".getBytes(StandardCharsets.US_ASCII));

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream());
            InputStream rawIn = new BufferedInputStream(socket.getInputStream());

            String req = ""
                    + "GET /ws/echo HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            rawOut.write(req.getBytes(StandardCharsets.US_ASCII));
            rawOut.flush();

            String status = readHttpHeaders(rawIn);
            assertTrue(status.startsWith("HTTP/1.1 101"), status);
            assertTrue(status.toLowerCase().contains("upgrade: websocket"));
            String expectedAccept = accept(key);
            assertTrue(status.contains(expectedAccept), status);

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (opened.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("/ws/echo", opened.get());

            // Client text frame (must be masked)
            byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
            writeMaskedText(rawOut, payload);

            WebSocketFrames.Frame frame = WebSocketFrames.read(rawIn);
            assertEquals(WebSocketFrames.OPCODE_TEXT, frame.opcode());
            assertEquals("echo:ping", frame.text());

            // Close
            WebSocketFrames.write(rawOut, WebSocketFrames.OPCODE_CLOSE, new byte[]{0x03, (byte) 0xE8}, true);
            rawOut.flush();
        }

        assertTrue(closed.await(3, TimeUnit.SECONDS));
    }

    @Test
    void acceptKeyMatchesRfc6455Example() {
        // RFC 6455 section 1.3 example
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketSupport.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    private static String accept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((key + WebSocketSupport.MAGIC).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static void writeMaskedText(OutputStream out, byte[] payload) throws Exception {
        byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
        out.write(0x81); // FIN + text
        out.write(0x80 | payload.length);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        out.flush();
    }
}
