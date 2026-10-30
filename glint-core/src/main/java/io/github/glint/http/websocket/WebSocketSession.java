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

/**
 * An established WebSocket connection after a successful HTTP/1.1 Upgrade.
 * Frames are read/written with blocking I/O on the connection's virtual thread.
 */
public final class WebSocketSession implements AutoCloseable {

    private final Socket socket;
    private final BufferedInputStream in;
    private final OutputStream out;
    private final String path;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

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
        WebSocketFrames.write(out, WebSocketFrames.OPCODE_TEXT, payload, false);
        out.flush();
    }

    /** Send a binary frame (opcode 0x2). */
    public void sendBinary(byte[] data) throws IOException {
        ensureOpen();
        WebSocketFrames.write(out, WebSocketFrames.OPCODE_BINARY, data == null ? new byte[0] : data, false);
        out.flush();
    }

    /** Send a close frame and mark the session closed. */
    public void close(int code, String reason) throws IOException {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + Math.min(reasonBytes.length, 123)];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, payload.length - 2);
            WebSocketFrames.write(out, WebSocketFrames.OPCODE_CLOSE, payload, false);
            out.flush();
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

    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
    }
}
