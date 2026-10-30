package io.github.glint.http.websocket;

/**
 * Application callback for a WebSocket endpoint registered via {@code Glint.websocket}.
 *
 * <p>Default methods make echo endpoints a one-liner:
 * <pre>{@code
 * .websocket("/ws/echo", (session, text) -> session.sendText(text))
 * }</pre>
 *
 * <p>Binary frames, ping, and close codes are available via the default methods below.
 */
@FunctionalInterface
public interface WebSocketHandler {

    /** Called when a text frame arrives. */
    void onMessage(WebSocketSession session, String text) throws Exception;

    /** Called once after the HTTP Upgrade handshake succeeds. */
    default void onOpen(WebSocketSession session) throws Exception {
        // optional
    }

    /**
     * Called when the connection closes (peer close, error, or local close).
     * Prefer {@link #onClose(WebSocketSession, int, String)} for status codes.
     */
    default void onClose(WebSocketSession session) {
        // optional
    }

    /**
     * Called when the connection closes, with the RFC 6455 status code and reason.
     * Default delegates to {@link #onClose(WebSocketSession)}.
     *
     * @param code   close status (e.g. 1000), or {@code -1} if unknown
     * @param reason UTF-8 reason string (may be empty)
     */
    default void onClose(WebSocketSession session, int code, String reason) {
        onClose(session);
    }

    /** Called for binary frames. Default implementation ignores them. */
    default void onBinary(WebSocketSession session, byte[] data) throws Exception {
        // optional
    }

    /**
     * Called when a ping arrives. Default replies with a matching pong
     * (also done by the frame pump; override to observe only).
     */
    default void onPing(WebSocketSession session, byte[] payload) throws Exception {
        // optional observation; pump already replies with pong
    }

    /** Called when a pong arrives (e.g. in response to {@link WebSocketSession#ping}). */
    default void onPong(WebSocketSession session, byte[] payload) throws Exception {
        // optional
    }
}
