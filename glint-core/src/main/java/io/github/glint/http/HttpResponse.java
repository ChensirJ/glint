package io.github.glint.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streaming HTTP/1.1 response writer.
 * Call {@link #text(String)}, {@link #json(String)}, or {@link #bytes(byte[], String)}
 * then the server will invoke {@link #finish()} after the handler returns.
 */
public final class HttpResponse {

    private static final String CRLF = new String(new char[]{13, 10});

    private final OutputStream out;
    private int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private boolean committed;

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

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse text(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        headers.putIfAbsent("Content-Type", "text/plain; charset=utf-8");
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

    /** Write status line, headers, and body to the socket. Idempotent after first call. */
    public void finish() throws IOException {
        if (committed) {
            return;
        }
        committed = true;
        headers.put("Content-Length", Integer.toString(body.length));
        headers.putIfAbsent("Connection", "keep-alive");

        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append(CRLF);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append(CRLF);
        }
        sb.append(CRLF);
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static String reason(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }
}
