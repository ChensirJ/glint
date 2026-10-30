package io.github.glint.http.sse;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SseEmitterTest {

    private Glint glint;

    @AfterEach
    void tearDown() {
        if (glint != null) {
            glint.stop();
        }
    }

    @Test
    void streamsEventsCommentsAndCloses() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(3_000).shutdownWaitMs(1_000).build())
                .get("/events", (req, resp) -> {
                    try (SseEmitter sse = SseEmitter.start(resp)) {
                        sse.comment("connected");
                        sse.send(SseEvent.builder().id("1").event("hello").data("world").build());
                        sse.send("line1\nline2");
                        sends.incrementAndGet();
                    }
                })
                .startAsync();

        int port = glint.port();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write((
                    "GET /events HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

            String joined = String.join("\n", lines);
            assertTrue(joined.contains("HTTP/1.1 200"), joined);
            assertTrue(joined.toLowerCase().contains("text/event-stream"), joined);
            assertTrue(joined.toLowerCase().contains("connection: close"), joined);
            assertTrue(joined.contains(": connected"), joined);
            assertTrue(joined.contains("event: hello"), joined);
            assertTrue(joined.contains("data: world"), joined);
            assertTrue(joined.contains("data: line1"), joined);
            assertTrue(joined.contains("data: line2"), joined);
            assertEquals(1, sends.get());
        }
    }
}