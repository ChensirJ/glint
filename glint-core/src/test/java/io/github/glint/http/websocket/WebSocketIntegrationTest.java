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
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Raw-socket WebSocket upgrade covering text, binary, ping/pong, and close codes.
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
                    public void onClose(WebSocketSession session, int code, String reason) {
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

            handshake(rawOut, rawIn, "/ws/echo", key);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (opened.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("/ws/echo", opened.get());

            writeMasked(rawOut, WebSocketFrames.OPCODE_TEXT, "ping".getBytes(StandardCharsets.UTF_8));

            WebSocketFrames.Frame frame = WebSocketFrames.read(rawIn);
            assertEquals(WebSocketFrames.OPCODE_TEXT, frame.opcode());
            assertEquals("echo:ping", frame.text());

            WebSocketFrames.write(rawOut, WebSocketFrames.OPCODE_CLOSE, new byte[]{0x03, (byte) 0xE8}, true);
            rawOut.flush();
        }

        assertTrue(closed.await(3, TimeUnit.SECONDS));
    }

    @Test
    void binaryEchoPingPongAndCloseCode() throws Exception {
        AtomicInteger closedCode = new AtomicInteger(-1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<byte[]> pingSeen = new AtomicReference<>();

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(5_000).shutdownWaitMs(1_000).build())
                .websocket("/ws/bin", new WebSocketHandler() {
                    @Override
                    public void onMessage(WebSocketSession session, String text) {
                        // unused
                    }

                    @Override
                    public void onBinary(WebSocketSession session, byte[] data) throws Exception {
                        session.sendBinary(data);
                    }

                    @Override
                    public void onPing(WebSocketSession session, byte[] payload) {
                        pingSeen.set(payload);
                    }

                    @Override
                    public void onClose(WebSocketSession session, int code, String reason) {
                        closedCode.set(code);
                        closed.countDown();
                    }
                })
                .startAsync();

        int port = glint.port();
        String key = Base64.getEncoder().encodeToString("glint-bin-key-1234".getBytes(StandardCharsets.US_ASCII));

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream());
            InputStream rawIn = new BufferedInputStream(socket.getInputStream());
            handshake(rawOut, rawIn, "/ws/bin", key);

            byte[] blob = new byte[]{0x01, 0x02, 0x03, (byte) 0xFE};
            writeMasked(rawOut, WebSocketFrames.OPCODE_BINARY, blob);
            WebSocketFrames.Frame bin = WebSocketFrames.read(rawIn);
            assertEquals(WebSocketFrames.OPCODE_BINARY, bin.opcode());
            assertArrayEquals(blob, bin.payload());

            byte[] pingPayload = "hi".getBytes(StandardCharsets.UTF_8);
            writeMasked(rawOut, WebSocketFrames.OPCODE_PING, pingPayload);
            WebSocketFrames.Frame pong = WebSocketFrames.read(rawIn);
            assertEquals(WebSocketFrames.OPCODE_PONG, pong.opcode());
            assertArrayEquals(pingPayload, pong.payload());

            // Close with code 1001 Going Away + reason
            byte[] reason = "bye".getBytes(StandardCharsets.UTF_8);
            byte[] closePayload = new byte[2 + reason.length];
            closePayload[0] = 0x03;
            closePayload[1] = (byte) 0xE9; // 1001
            System.arraycopy(reason, 0, closePayload, 2, reason.length);
            writeMasked(rawOut, WebSocketFrames.OPCODE_CLOSE, closePayload);

            // Server should echo a close frame
            WebSocketFrames.Frame closeAck = WebSocketFrames.read(rawIn);
            assertEquals(WebSocketFrames.OPCODE_CLOSE, closeAck.opcode());
            assertTrue(closeAck.payload().length >= 2);
            int code = ((closeAck.payload()[0] & 0xFF) << 8) | (closeAck.payload()[1] & 0xFF);
            assertEquals(1001, code);
        }

        assertTrue(closed.await(3, TimeUnit.SECONDS));
        assertEquals(1001, closedCode.get());
        assertNotNull(pingSeen.get());
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), pingSeen.get());
    }

    @Test
    void acceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketSupport.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    private static void handshake(OutputStream rawOut, InputStream rawIn, String path, String key) throws Exception {
        String req = ""
                + "GET " + path + " HTTP/1.1\r\n"
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
        assertTrue(status.contains(accept(key)), status);
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

    private static void writeMasked(OutputStream out, int opcode, byte[] payload) throws Exception {
        byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
        out.write(0x80 | (opcode & 0x0F));
        out.write(0x80 | payload.length);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        out.flush();
    }

    @Test
    void fragmentedTextMessageIsReassembled() throws Exception {
        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(5_000).shutdownWaitMs(1_000).build())
                .websocket("/ws/frag", (session, text) -> {
                    got.set(text);
                    session.sendText("got:" + text);
                    latch.countDown();
                })
                .startAsync();

        int port = glint.port();
        String key = Base64.getEncoder().encodeToString("glint-frag-key-12".getBytes(StandardCharsets.US_ASCII));

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream());
            InputStream rawIn = new BufferedInputStream(socket.getInputStream());
            handshake(rawOut, rawIn, "/ws/frag", key);

            // "Hello" as FIN=0 TEXT "He" + FIN=1 CONT "llo"
            writeMaskedFin(rawOut, WebSocketFrames.OPCODE_TEXT, false, "He".getBytes(StandardCharsets.UTF_8));
            writeMaskedFin(rawOut, WebSocketFrames.OPCODE_CONTINUATION, true, "llo".getBytes(StandardCharsets.UTF_8));

            WebSocketFrames.Frame frame = WebSocketFrames.read(rawIn);
            assertEquals(WebSocketFrames.OPCODE_TEXT, frame.opcode());
            assertEquals("got:Hello", frame.text());
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("Hello", got.get());
    }

    private static void writeMaskedFin(OutputStream out, int opcode, boolean fin, byte[] payload) throws Exception {
        byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
        out.write((fin ? 0x80 : 0x00) | (opcode & 0x0F));
        out.write(0x80 | payload.length);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        out.flush();
    }
}
