package io.github.glint.servlet;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the servlet adapter emits multiple Set-Cookie header lines via Glint.
 */
class MultiCookieServletTest {

    @Test
    void multipleCookiesBecomeSeparateSetCookieLines() throws Exception {
        GlintServletContext ctx = new GlintServletContext();
        ctx.addServlet("s", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
                Cookie a = new Cookie("a", "1");
                a.setPath("/");
                Cookie b = new Cookie("b", "2");
                b.setPath("/");
                b.setHttpOnly(true);
                resp.addCookie(a);
                resp.addCookie(b);
                resp.setContentType("text/plain");
                resp.getWriter().write("ok");
            }
        }).addMapping("/*");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse gr = new HttpResponse(out);
        GlintHttpServletResponse sr = new GlintHttpServletResponse(gr);
        GlintHttpServletRequest req = new GlintHttpServletRequest(
                new HttpRequest(HttpMethod.GET, "/", Map.of(), new byte[0]), ctx, sr);
        ctx.findServletForPath("/").service(req, sr);
        sr.commitToGlint();
        gr.finish();

        String raw = out.toString(StandardCharsets.US_ASCII);
        long setCookieLines = raw.lines().filter(l -> l.startsWith("Set-Cookie:")).count();
        assertEquals(2, setCookieLines, raw);
        assertTrue(raw.contains("Set-Cookie: a=1; Path=/"), raw);
        assertTrue(raw.contains("Set-Cookie: b=2; Path=/; HttpOnly"), raw);
    }
}
