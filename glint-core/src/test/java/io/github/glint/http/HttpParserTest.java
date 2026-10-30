package io.github.glint.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpParserTest {

    @Test
    void parsesGetWithHeaders() throws Exception {
        String raw = "GET /hello?x=1 HTTP/1.1"
                + (char) 13 + (char) 10
                + "Host: localhost"
                + (char) 13 + (char) 10
                + "Connection: keep-alive"
                + (char) 13 + (char) 10
                + (char) 13 + (char) 10;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals(HttpMethod.GET, req.method());
        assertEquals("/hello", req.path());
        assertEquals("x=1", req.query());
        assertEquals("localhost", req.header("host").orElseThrow());
        assertTrue(req.keepAlive());
        assertEquals(0, req.bodyBytes().length);
    }

    @Test
    void parsesPostWithBody() throws Exception {
        String body = new String(new char[]{'{', '"', 'a', '"', ':', '1', '}'});
        String raw = "POST /orders HTTP/1.1"
                + (char) 13 + (char) 10
                + "Host: localhost"
                + (char) 13 + (char) 10
                + "Content-Type: application/json"
                + (char) 13 + (char) 10
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + (char) 13 + (char) 10
                + (char) 13 + (char) 10
                + body;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals(HttpMethod.POST, req.method());
        assertEquals("/orders", req.path());
        assertEquals(body, req.bodyText());
        assertEquals("application/json", req.header("content-type").orElseThrow());
    }

    @Test
    void returnsNullOnEmptyStream() throws Exception {
        assertNull(HttpParser.parseString(""));
    }
}
