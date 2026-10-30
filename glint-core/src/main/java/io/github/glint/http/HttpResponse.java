package io.github.glint.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streaming HTTP/1.1 response writer.
 * Call {@link #text(String)}, {@link #json(String)}, or {@link #bytes(byte[], String)}
 * then the server invokes {@link #finish()} / {@link #finish(HttpMethod)} after the handler.
 *
 * <p>For HEAD requests the headers are written but the body is omitted.
 */
public final class HttpResponse {

    private static final byte[] CRLF = new byte[]{13, 10};

    private final OutputStream out;
    private int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
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

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse text(String body) {
        return text(body, StandardCharsets.UTF_8);
    }

    public HttpResponse text(String body, Charset charset) {
        this.body = body.getBytes(charset);
        headers.putIfAbsent("Content-Type", "text/plain; charset=" + charset.name().toLowerCase());
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
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (!head && body.length > 0) {
            out.write(body);
        }
        out.flush();
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
