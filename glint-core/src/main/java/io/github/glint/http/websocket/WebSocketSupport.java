package io.github.glint.http.websocket;

import io.github.glint.http.HttpRequest;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP/1.1 {@code Upgrade: websocket} handshake and frame pump for Glint.
 *
 * <p>Supports text, binary, ping/pong, and close frames with status codes.
 * Fragmented messages and extensions are intentionally out of scope for 0.1.
 */
public final class WebSocketSupport {

    /** RFC 6455 magic GUID. */
    public static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketSupport() {
    }

    /** Registry of path → handler installed by {@link io.github.glint.Glint}. */
    public static final class Registry {
        private final Map<String, WebSocketHandler> routes = new ConcurrentHashMap<>();

        public void add(String path, WebSocketHandler handler) {
            if (path == null || path.isEmpty() || !path.startsWith("/")) {
                throw new IllegalArgumentException("WebSocket path must start with /");
            }
            routes.put(path, handler);
        }

        public WebSocketHandler find(String path) {
            return routes.get(path);
        }

        public boolean isEmpty() {
            return routes.isEmpty();
        }
    }

    public static boolean isUpgradeRequest(HttpRequest req) {
        String upgrade = req.header("upgrade").orElse("").toLowerCase(Locale.ROOT);
        String connection = req.header("connection").orElse("").toLowerCase(Locale.ROOT);
        String key = req.header("sec-websocket-key").orElse("");
        String version = req.header("sec-websocket-version").orElse("");
        return upgrade.contains("websocket")
                && connection.contains("upgrade")
                && !key.isBlank()
                && "13".equals(version.trim());
    }

    public static String acceptKey(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((secWebSocketKey.trim() + MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    /**
     * Write the {@code 101 Switching Protocols} response.
     * Does not close the streams  -  the caller enters the frame loop next.
     */
    public static void writeHandshake(OutputStream out, HttpRequest req) throws IOException {
        String key = req.header("sec-websocket-key").orElseThrow();
        String accept = acceptKey(key);
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 101 Switching Protocols\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        sb.append("Server: Glint/0.1\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Complete handshake and pump frames until close.
     *
     * @return true if this request was handled as WebSocket (caller should end the HTTP loop)
     */
    public static boolean maybeUpgrade(Registry registry,
                                       HttpRequest req,
                                       Socket socket,
                                       BufferedInputStream in,
                                       OutputStream out) throws Exception {
        if (registry == null || registry.isEmpty() || !isUpgradeRequest(req)) {
            return false;
        }
        WebSocketHandler handler = registry.find(req.path());
        if (handler == null) {
            return false;
        }
        writeHandshake(out, req);
        WebSocketSession session = new WebSocketSession(socket, in, out, req.path());
        try {
            handler.onOpen(session);
            pump(session, handler);
        } finally {
            session.markClosed();
            handler.onClose(session, session.closeCode(), session.closeReason());
        }
        return true;
    }

    static void pump(WebSocketSession session, WebSocketHandler handler) throws Exception {
        BufferedInputStream in = session.input();
        OutputStream out = session.output();
        while (session.isOpen()) {
            WebSocketFrames.Frame frame;
            try {
                frame = WebSocketFrames.read(in);
            } catch (EOFException eof) {
                break;
            }
            switch (frame.opcode()) {
                case WebSocketFrames.OPCODE_TEXT -> handler.onMessage(session, frame.text());
                case WebSocketFrames.OPCODE_BINARY -> handler.onBinary(session, frame.payload());
                case WebSocketFrames.OPCODE_PING -> {
                    // Auto-reply with pong (RFC 6455 §5.5.2), then notify the app.
                    synchronized (out) {
                        WebSocketFrames.write(out, WebSocketFrames.OPCODE_PONG, frame.payload(), false);
                        out.flush();
                    }
                    handler.onPing(session, frame.payload());
                }
                case WebSocketFrames.OPCODE_PONG -> handler.onPong(session, frame.payload());
                case WebSocketFrames.OPCODE_CLOSE -> {
                    int code = 1000;
                    String reason = "";
                    byte[] payload = frame.payload();
                    if (payload.length >= 2) {
                        code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        if (payload.length > 2) {
                            reason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                        }
                    }
                    session.recordPeerClose(code, reason);
                    // Echo a close frame back if still open (RFC 6455 §5.5.1).
                    if (session.isOpen()) {
                        session.close(code, reason.isEmpty() ? "ack" : reason);
                    }
                    return;
                }
                default -> {
                    // ignore continuation / reserved for 0.1
                }
            }
        }
    }

    /** @deprecated Loud failure for accidental calls to the old stub API. */
    @Deprecated
    public static void unsupported() {
        throw new UnsupportedOperationException(
                "Use Glint.websocket(path, handler)  -  WebSocket upgrade is implemented.");
    }
}
