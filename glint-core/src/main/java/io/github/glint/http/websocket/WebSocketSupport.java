package io.github.glint.http.websocket;

/**
 * Placeholder for future HTTP/1.1 {@code Upgrade: websocket} support.
 *
 * <p><strong>Not implemented in 0.1.</strong> Glint currently serves HTTP/1.1
 * request/response only. When WebSocket lands it will:
 * <ul>
 *   <li>Detect {@code Connection: Upgrade} + {@code Upgrade: websocket}</li>
 *   <li>Complete the handshake (Sec-WebSocket-Accept)</li>
 *   <li>Hand the raw socket to a virtual-thread frame reader/writer</li>
 * </ul>
 *
 * <p>Tracking: see project README roadmap. Calling any method here throws
 * {@link UnsupportedOperationException} so accidental use fails loudly.
 */
public final class WebSocketSupport {

    private WebSocketSupport() {
    }

    /** Always throws — WebSocket upgrade is not available yet. */
    public static void unsupported() {
        throw new UnsupportedOperationException(
                "WebSocket / HTTP Upgrade is not implemented in Glint 0.1. "
                        + "See README roadmap. Use SSE or short polling for now.");
    }
}
