package io.github.glint.http.sse;

import io.github.glint.http.HttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * First-class helper for {@code text/event-stream} responses.
 *
 * <p>Commits SSE response headers immediately, then writes events until
 * {@link #close()}. Closing ends the stream and marks the connection for close
 * (no keep-alive reuse). Client disconnect surfaces as {@link IOException} from
 * {@link #send}; callers should treat that as a clean end.
 *
 * <pre>{@code
 * try (SseEmitter sse = SseEmitter.start(resp)) {
 *     sse.send(SseEvent.builder().event("hello").data("world").build());
 *     sse.comment("keep-alive");
 * }
 * }</pre>
 */
public final class SseEmitter implements AutoCloseable {

    private final HttpResponse response;
    private volatile boolean open = true;

    private SseEmitter(HttpResponse response) {
        this.response = response;
    }

    /**
     * Start an SSE response on {@code response} (status 200, {@code text/event-stream},
     * {@code Cache-Control: no-cache}, {@code Connection: close}).
     */
    public static SseEmitter start(HttpResponse response) throws IOException {
        Objects.requireNonNull(response, "response");
        response.startEventStream();
        return new SseEmitter(response);
    }

    public boolean isOpen() {
        return open && response.streaming();
    }

    /** Write a fully formed event. */
    public void send(SseEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        ensureOpen();
        response.writeStream(event.format().getBytes(StandardCharsets.UTF_8));
    }

    /** Write a data-only event. */
    public void send(String data) throws IOException {
        send(SseEvent.data(data));
    }

    /**
     * Write an SSE comment line ({@code : ...}). Useful as a keep-alive heartbeat;
     * browsers ignore comments.
     */
    public void comment(String text) throws IOException {
        ensureOpen();
        String safe = text == null ? "" : SseEvent.sanitizeField(text);
        response.writeStream((": " + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Flush any buffered stream bytes to the client. */
    public void flush() throws IOException {
        ensureOpen();
        response.flushStream();
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        response.endEventStream();
    }

    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("SSE stream closed");
        }
    }
}