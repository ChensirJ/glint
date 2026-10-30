package io.github.glint.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpParserTest {

    private static final char CR = 13;
    private static final char LF = 10;

    private static String crlf(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(CR).append(LF);
        }
        return sb.toString();
    }

    @Test
    void parsesGetWithHeadersAndQuery() throws Exception {
        String raw = crlf(
                "GET /hello?x=1&y=hi%20there HTTP/1.1",
                "Host: localhost",
                "Connection: keep-alive",
                "") ;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals(HttpMethod.GET, req.method());
        assertEquals("/hello", req.path());
        assertEquals("x=1&y=hi%20there", req.query());
        assertEquals("1", req.queryParam("x").orElseThrow());
        assertEquals("hi there", req.queryParam("y").orElseThrow());
        assertEquals("localhost", req.header("host").orElseThrow());
        assertTrue(req.keepAlive());
        assertEquals(0, req.bodyBytes().length);
    }

    @Test
    void parsesPostWithBody() throws Exception {
        String body = "{\"a\":1}";
        String raw = crlf(
                "POST /orders HTTP/1.1",
                "Host: localhost",
                "Content-Type: application/json",
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length,
                "") + body;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals(HttpMethod.POST, req.method());
        assertEquals("/orders", req.path());
        assertEquals(body, req.bodyText());
        assertEquals("application/json", req.header("content-type").orElseThrow());
    }

    @Test
    void parsesChunkedBody() throws Exception {
        String raw = crlf(
                "POST /echo HTTP/1.1",
                "Host: localhost",
                "Transfer-Encoding: chunked",
                "")
                + "5" + CR + LF + "hello" + CR + LF
                + "1" + CR + LF + "!" + CR + LF
                + "0" + CR + LF + CR + LF;
        HttpRequest req = HttpParser.parseString(raw);
        assertEquals("hello!", req.bodyText());
    }

    @Test
    void rejectsOversizedBody() {
        ServerConfig cfg = ServerConfig.builder().maxRequestBodyBytes(4).build();
        String raw = crlf(
                "POST /x HTTP/1.1",
                "Host: localhost",
                "Content-Length: 5",
                "") + "hello";
        HttpException ex = assertThrows(HttpException.class, () -> HttpParser.parseString(raw, cfg));
        assertEquals(413, ex.status());
    }

    @Test
    void returnsNullOnEmptyStream() throws Exception {
        assertNull(HttpParser.parseString(""));
    }
}
