package io.github.glint.http.websocket;

/**
 * Application callback for a WebSocket endpoint registered via {@code Glint.websocket}.
 *
 * <p>Default methods make echo endpoints a one-liner:
 * <pre>{@code
 * .websocket("/ws/echo", (session, text) -> session.sendText(text))
 * }</pre>
 */
@FunctionalInterface
public interface WebSocketHandler {

    /** Called when a text frame arrives. */
    void onMessage(WebSocketSession session, String text) throws Exception;

    /** Called once after the HTTP Upgrade handshake succeeds. */
    default void onOpen(WebSocketSession session) throws Exception {
        // optional
    }

    /** Called when the connection closes (peer close, error, or local close). */
    default void onClose(WebSocketSession session) {
        // optional
    }

    /** Called for binary frames. Default implementation ignores them. */
    default void onBinary(WebSocketSession session, byte[] data) throws Exception {
        // optional
    }
}
