package io.github.glint.http.websocket;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An established WebSocket connection after a successful HTTP/1.1 Upgrade.
 * Frames are read/written with blocking I/O on the connection's virtual thread.
 *
 * <p>Supports text, binary, ping, pong, and close frames with status codes.
 */
public final class WebSocketSession implements AutoCloseable {

    private final Socket socket;
    private final BufferedInputStream in;
    private final OutputStream out;
    private final String path;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger closeCode = new AtomicInteger(-1);
    private final AtomicReference<String> closeReason = new AtomicReference<>("");

    WebSocketSession(Socket socket, BufferedInputStream in, OutputStream out, String path) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.path = path == null ? "/" : path;
    }

    public String path() {
        return path;
    }

    public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    /** RFC 6455 close code from the peer or local close, or {@code -1} if unknown. */
    public int closeCode() {
        return closeCode.get();
    }

    /** Close reason UTF-8 text, or empty. */
    public String closeReason() {
        String r = closeReason.get();
        return r == null ? "" : r;
    }

    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    /** Send a text frame (opcode 0x1). Not masked (server → client). */
    public void sendText(String text) throws IOException {
        ensureOpen();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            WebSocketFrames.write(out, WebSocketFrames.OPCODE_TEXT, payload, false);
            out.flush();
        }
    }

    /** Send a binary frame (opcode 0x2). */
    public void sendBinary(byte[] data) throws IOException {
        ensureOpen();
        synchronized (out) {
            WebSocketFrames.write(out, WebSocketFrames.OPCODE_BINARY, data == null ? new byte[0] : data, false);
            out.flush();
        }
    }

    /** Send a ping control frame (opcode 0x9). Payload max 125 bytes. */
    public void ping(byte[] payload) throws IOException {
        ensureOpen();
        byte[] data = truncateControl(payload);
        synchronized (out) {
            WebSocketFrames.write(out, WebSocketFrames.OPCODE_PING, data, false);
            out.flush();
        }
    }

    /** Send a pong control frame (opcode 0xA). Payload max 125 bytes. */
    public void pong(byte[] payload) throws IOException {
        ensureOpen();
        byte[] data = truncateControl(payload);
        synchronized (out) {
            WebSocketFrames.write(out, WebSocketFrames.OPCODE_PONG, data, false);
            out.flush();
        }
    }

    /**
     * Send a close frame with an RFC 6455 status code and optional UTF-8 reason,
     * then close the underlying socket.
     *
     * @param code   e.g. 1000 (normal), 1001 (going away), 1008 (policy)
     * @param reason optional reason (truncated to fit in a control frame)
     */
    public void close(int code, String reason) throws IOException {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        closeCode.set(code);
        closeReason.set(reason == null ? "" : reason);
        try {
            byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            int reasonLen = Math.min(reasonBytes.length, 123);
            byte[] payload = new byte[2 + reasonLen];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonLen);
            synchronized (out) {
                WebSocketFrames.write(out, WebSocketFrames.OPCODE_CLOSE, payload, false);
                out.flush();
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Override
    public void close() throws IOException {
        close(1000, "bye");
    }

    BufferedInputStream input() {
        return in;
    }

    OutputStream output() {
        return out;
    }

    void markClosed() {
        open.set(false);
    }

    void recordPeerClose(int code, String reason) {
        closeCode.set(code);
        closeReason.set(reason == null ? "" : reason);
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
    }

    private static byte[] truncateControl(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new byte[0];
        }
        if (payload.length <= 125) {
            return payload;
        }
        byte[] truncated = new byte[125];
        System.arraycopy(payload, 0, truncated, 0, 125);
        return truncated;
    }
}
