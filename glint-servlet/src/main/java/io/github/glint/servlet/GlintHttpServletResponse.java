package io.github.glint.servlet;

import io.github.glint.http.HttpResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal {@link HttpServletResponse} that buffers the body and flushes into
 * a Glint {@link HttpResponse} when {@link #commitToGlint()} is called.
 */
public final class GlintHttpServletResponse implements HttpServletResponse {

    private final HttpResponse glint;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int status = 200;
    private String contentType = "text/plain; charset=utf-8";
    private String characterEncoding = StandardCharsets.UTF_8.name();
    private boolean committed;
    private PrintWriter writer;
    private ServletOutputStream outputStream;

    public GlintHttpServletResponse(HttpResponse glint) {
        this.glint = glint;
    }

    /** Copy buffered status/headers/body into the underlying Glint response. */
    public void commitToGlint() throws IOException {
        if (committed) {
            return;
        }
        if (writer != null) {
            writer.flush();
        }
        committed = true;
        glint.status(status);
        if (contentType != null) {
            glint.header("Content-Type", contentType);
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            glint.header(e.getKey(), e.getValue());
        }
        glint.bytes(buffer.toByteArray(), contentType);
    }

    @Override public void addCookie(Cookie cookie) { }
    @Override public boolean containsHeader(String name) { return headers.containsKey(name); }
    @Override public String encodeURL(String url) { return url; }
    @Override public String encodeRedirectURL(String url) { return url; }
    @Override public void sendError(int sc, String msg) throws IOException {
        setStatus(sc);
        resetBuffer();
        getWriter().write(msg == null ? "" : msg);
    }
    @Override public void sendError(int sc) throws IOException { sendError(sc, null); }
    @Override public void sendRedirect(String location) throws IOException {
        setStatus(302);
        setHeader("Location", location);
    }
    @Override public void setDateHeader(String name, long date) { setHeader(name, Long.toString(date)); }
    @Override public void addDateHeader(String name, long date) { addHeader(name, Long.toString(date)); }
    @Override public void setHeader(String name, String value) { headers.put(name, value); }
    @Override public void addHeader(String name, String value) { headers.put(name, value); }
    @Override public void setIntHeader(String name, int value) { setHeader(name, Integer.toString(value)); }
    @Override public void addIntHeader(String name, int value) { addHeader(name, Integer.toString(value)); }
    @Override public void setStatus(int sc) { this.status = sc; }
    @Override public int getStatus() { return status; }
    @Override public String getHeader(String name) { return headers.get(name); }
    @Override public Collection<String> getHeaders(String name) {
        String v = headers.get(name);
        return v == null ? Collections.emptyList() : Collections.singletonList(v);
    }
    @Override public Collection<String> getHeaderNames() { return headers.keySet(); }

    @Override public String getCharacterEncoding() { return characterEncoding; }
    @Override public String getContentType() { return contentType; }
    @Override public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter() already called");
        }
        if (outputStream == null) {
            outputStream = new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener writeListener) { }
                @Override public void write(int b) { buffer.write(b); }
            };
        }
        return outputStream;
    }
    @Override public PrintWriter getWriter() {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() already called");
        }
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(buffer, Charset.forName(characterEncoding)));
        }
        return writer;
    }
    @Override public void setCharacterEncoding(String charset) { this.characterEncoding = charset; }
    @Override public void setContentLength(int len) { setHeader("Content-Length", Integer.toString(len)); }
    @Override public void setContentLengthLong(long len) { setHeader("Content-Length", Long.toString(len)); }
    @Override public void setContentType(String type) {
        this.contentType = type;
        if (type != null) {
            headers.put("Content-Type", type);
        }
    }
    @Override public void setBufferSize(int size) { }
    @Override public int getBufferSize() { return buffer.size(); }
    @Override public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
    }
    @Override public void resetBuffer() { buffer.reset(); }
    @Override public boolean isCommitted() { return committed; }
    @Override public void reset() {
        buffer.reset();
        headers.clear();
        status = 200;
    }
    @Override public void setLocale(Locale loc) { }
    @Override public Locale getLocale() { return Locale.getDefault(); }
}
