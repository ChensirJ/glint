package io.github.glint.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streaming HTTP/1.1 response writer.
 * Call {@link #text(String)}, {@link #json(String)}, or {@link #bytes(byte[], String)}
 * then the server invokes {@link #finish()} / {@link #finish(HttpMethod)} after the handler.
 *
 * <p>For HEAD requests the headers are written but the body is omitted.
 *
 * <p>{@code Set-Cookie} is special-cased: each {@link #cookie(String)} / {@link #header(String, String)}
 * call with name {@code Set-Cookie} appends another cookie line. HTTP/1.1 requires a separate
 * {@code Set-Cookie} header field per cookie (comma-joining is invalid for cookies).
 */
public final class HttpResponse {

    private final OutputStream out;
    private int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    /** Ordered list of Set-Cookie header values (one wire header per entry). */
    private final List<String> setCookies = new ArrayList<>();
    private byte[] body = new byte[0];
    private boolean committed;
    private boolean suppressBody;

    public HttpResponse(OutputStream out) {
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out);
        headers.put("Server", "Glint/0.1");
    }

    public HttpResponse status(int code) {
        this.status = code;
        return this;
    }

    public int status() {
        return status;
    }

    public boolean committed() {
        return committed;
    }

    /** When true, {@link #finish()} writes headers only (used for HEAD). */
    public HttpResponse suppressBody(boolean value) {
        this.suppressBody = value;
        return this;
    }

    /**
     * Set a single-valued header. For {@code Set-Cookie}, appends instead of replacing
     * so multiple cookies can be emitted correctly.
     */
    public HttpResponse header(String name, String value) {
        if (isSetCookie(name)) {
            return cookie(value);
        }
        headers.put(name, value);
        return this;
    }

    /**
     * Append a header value. Non-cookie headers that already exist are comma-joined
     * (RFC 9110). {@code Set-Cookie} always appends a distinct header line.
     */
    public HttpResponse addHeader(String name, String value) {
        if (isSetCookie(name)) {
            return cookie(value);
        }
        String existing = headers.get(name);
        if (existing == null || existing.isEmpty()) {
            headers.put(name, value);
        } else {
            headers.put(name, existing + ", " + value);
        }
        return this;
    }

    /**
     * Append one {@code Set-Cookie} header value (already formatted, e.g.
     * {@code name=value; Path=/; HttpOnly}).
     */
    public HttpResponse cookie(String setCookieValue) {
        if (setCookieValue != null && !setCookieValue.isBlank()) {
            setCookies.add(setCookieValue.trim());
        }
        return this;
    }

    /**
     * Build and append a {@code Set-Cookie} line from common attributes.
     *
     * @param name     cookie name (required)
     * @param value    cookie value (may be empty)
     * @param path     Path attribute, or {@code null} to omit
     * @param maxAge   Max-Age seconds, or {@code null} to omit
     * @param httpOnly whether to set HttpOnly
     * @param secure   whether to set Secure
     */
    public HttpResponse cookie(String name,
                               String value,
                               String path,
                               Integer maxAge,
                               boolean httpOnly,
                               boolean secure) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("cookie name required");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value == null ? "" : value);
        if (path != null && !path.isEmpty()) {
            sb.append("; Path=").append(path);
        }
        if (maxAge != null) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (secure) {
            sb.append("; Secure");
        }
        return cookie(sb.toString());
    }

    /** Snapshot of queued Set-Cookie values (unmodifiable). */
    public List<String> cookies() {
        return Collections.unmodifiableList(setCookies);
    }

    public HttpResponse text(String body) {
        return text(body, StandardCharsets.UTF_8);
    }

    public HttpResponse text(String body, Charset charset) {
        this.body = body.getBytes(charset);
        headers.putIfAbsent("Content-Type", "text/plain; charset=" + charset.name().toLowerCase(Locale.ROOT));
        return this;
    }

    public HttpResponse json(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        headers.putIfAbsent("Content-Type", "application/json; charset=utf-8");
        return this;
    }

    public HttpResponse html(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        headers.putIfAbsent("Content-Type", "text/html; charset=utf-8");
        return this;
    }

    public HttpResponse bytes(byte[] data, String contentType) {
        this.body = data == null ? new byte[0] : data;
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        return this;
    }

    /** Convenience empty body with status (e.g. 204). */
    public HttpResponse empty() {
        this.body = new byte[0];
        return this;
    }

    public void finish() throws IOException {
        finish(null);
    }

    public void finish(HttpMethod method) throws IOException {
        if (committed) {
            return;
        }
        committed = true;
        boolean head = suppressBody || method == HttpMethod.HEAD;
        headers.put("Content-Length", Integer.toString(body.length));
        headers.putIfAbsent("Connection", "keep-alive");

        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            // Never write Set-Cookie from the map; cookies go through setCookies.
            if (isSetCookie(e.getKey())) {
                continue;
            }
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        for (String c : setCookies) {
            sb.append("Set-Cookie: ").append(c).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (!head && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static boolean isSetCookie(String name) {
        return name != null && "set-cookie".equalsIgnoreCase(name.trim());
    }

    static String reason(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }
}
