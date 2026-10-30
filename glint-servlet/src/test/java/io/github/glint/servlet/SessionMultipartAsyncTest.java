package io.github.glint.servlet;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Servlet-adapter coverage for sessions, multipart parts, and async complete.
 */
class SessionMultipartAsyncTest {

    @Test
    void sessionCookieRoundTrip() throws Exception {
        GlintServletContext ctx = new GlintServletContext();
        AtomicReference<String> sid = new AtomicReference<>();
        ctx.addServlet("s", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
                HttpSession session = req.getSession(true);
                sid.set(session.getId());
                session.setAttribute("n", 1);
                resp.setContentType("text/plain");
                resp.getWriter().write("sid=" + session.getId());
            }
        }).addMapping("/*");

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        HttpResponse gr1 = new HttpResponse(out1);
        GlintHttpServletResponse sr1 = new GlintHttpServletResponse(gr1);
        Map<String, String> headers = new LinkedHashMap<>();
        HttpRequest hr1 = new HttpRequest(HttpMethod.GET, "/x", headers, new byte[0]);
        GlintHttpServletRequest req1 = new GlintHttpServletRequest(hr1, ctx, sr1);
        ctx.findServletForPath("/x").service(req1, sr1);
        sr1.commitToGlint();
        gr1.finish();
        String raw1 = out1.toString(StandardCharsets.US_ASCII);
        assertTrue(raw1.contains("Set-Cookie: JSESSIONID="));
        assertNotNull(sid.get());

        // second request with cookie
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        HttpResponse gr2 = new HttpResponse(out2);
        GlintHttpServletResponse sr2 = new GlintHttpServletResponse(gr2);
        Map<String, String> h2 = new LinkedHashMap<>();
        h2.put("cookie", "JSESSIONID=" + sid.get());
        HttpRequest hr2 = new HttpRequest(HttpMethod.GET, "/x", h2, new byte[0]);
        GlintHttpServletRequest req2 = new GlintHttpServletRequest(hr2, ctx, sr2);
        HttpSession s2 = req2.getSession(false);
        assertNotNull(s2);
        assertEquals(sid.get(), s2.getId());
        assertEquals(1, s2.getAttribute("n"));
    }

    @Test
    void multipartPartsViaServletApi() throws Exception {
        String boundary = "----Bound";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + boundary + "--\r\n";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "multipart/form-data; boundary=" + boundary);
        HttpRequest hr = new HttpRequest(HttpMethod.POST, "/upload", headers, body.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GlintHttpServletResponse sr = new GlintHttpServletResponse(new HttpResponse(out));
        GlintHttpServletRequest req = new GlintHttpServletRequest(hr, new GlintServletContext(), sr);
        Collection<Part> parts = req.getParts();
        assertEquals(1, parts.size());
        Part p = req.getPart("title");
        assertNotNull(p);
        assertEquals("hello", new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void asyncCompleteCommits() throws Exception {
        GlintServletContext ctx = new GlintServletContext();
        ctx.addServlet("s", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                var async = req.startAsync();
                async.start(() -> {
                    try {
                        resp.setContentType("text/plain");
                        resp.getWriter().write("async-ok");
                        async.complete();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }).addMapping("/*");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse gr = new HttpResponse(out);
        new GlintServletDispatcher(ctx).handle(
                new HttpRequest(HttpMethod.GET, "/", Map.of(), new byte[0]), gr);
        gr.finish();
        String raw = out.toString(StandardCharsets.US_ASCII);
        assertTrue(raw.contains("async-ok"), raw);
    }
}
