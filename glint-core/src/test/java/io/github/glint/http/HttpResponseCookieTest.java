package io.github.glint.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures multiple Set-Cookie headers are emitted as separate header lines.
 */
class HttpResponseCookieTest {

    @Test
    void multipleSetCookieHeadersAreSeparateLines() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse resp = new HttpResponse(out);
        resp.cookie("a=1; Path=/");
        resp.cookie("b=2; Path=/; HttpOnly");
        resp.header("Set-Cookie", "c=3; Path=/");
        resp.text("ok");
        resp.finish();

        String raw = out.toString(StandardCharsets.US_ASCII);
        assertTrue(raw.contains("Set-Cookie: a=1; Path=/\r\n"), raw);
        assertTrue(raw.contains("Set-Cookie: b=2; Path=/; HttpOnly\r\n"), raw);
        assertTrue(raw.contains("Set-Cookie: c=3; Path=/\r\n"), raw);
        // Must not collapse into a single comma-joined Set-Cookie line.
        assertFalse(raw.lines().anyMatch(l -> l.startsWith("Set-Cookie:") && l.contains("a=1") && l.contains("b=2")));
        assertEquals(3, raw.lines().filter(l -> l.startsWith("Set-Cookie:")).count());
        assertEquals(List.of("a=1; Path=/", "b=2; Path=/; HttpOnly", "c=3; Path=/"), resp.cookies());
    }

    @Test
    void cookieBuilderEmitsAttributes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse resp = new HttpResponse(out);
        resp.cookie("sid", "xyz", "/", 3600, true, true);
        resp.empty();
        resp.finish();
        String raw = out.toString(StandardCharsets.US_ASCII);
        assertTrue(raw.contains("Set-Cookie: sid=xyz; Path=/; Max-Age=3600; HttpOnly; Secure\r\n"), raw);
    }
}
