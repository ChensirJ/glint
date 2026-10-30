package io.github.glint.http.sse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SseEventTest {

    @Test
    void formatsEventIdDataAndRetry() {
        String wire = SseEvent.builder()
                .id("42")
                .event("tick")
                .retry(3000)
                .data("hello")
                .build()
                .format();
        assertEquals("id: 42\nevent: tick\nretry: 3000\ndata: hello\n\n", wire);
    }

    @Test
    void splitsMultiLineData() {
        String wire = SseEvent.data("a\nb\nc").format();
        assertEquals("data: a\ndata: b\ndata: c\n\n", wire);
    }

    @Test
    void sanitizesFieldNewlines() {
        String wire = SseEvent.builder().event("x\ny").data("z").build().format();
        assertTrue(wire.startsWith("event: xy\n"));
        assertFalse(wire.contains("event: x\ny"));
    }
}