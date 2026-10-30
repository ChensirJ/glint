package io.github.glint.http;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;

import io.github.glint.obs.Trace;

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
 *
 * <p>Besides an in-memory body, a response can carry a <em>file region</em>
 * ({@link #file(Path, long, long, String)}). On plain-TCP connections the server hands
 * the socket channel to this class and the region is sent with
 * {@link FileChannel#transferTo}, letting the OS move bytes from page cache to the
 * socket (sendfile / TransmitFile) without copying them through a JVM buffer. When no
 * channel is available (TLS, tests, wrapped streams) the region is streamed instead.
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
    /** Socket channel for zero-copy sends, or null when only the stream may be used. */
    private final WritableByteChannel channel;
    private Path filePath;
    private long fileOffset;
    private long fileLength;
    /** True after {@link #startEventStream()} until the connection is done. */
    private boolean streaming;
    /** When true, VtServer must not reuse the socket for keep-alive. */
    private boolean closeConnection;
    /** Body bytes written after headers (streamed or finished). */
    private long bytesWritten;

    public HttpResponse(OutputStream out) {
        this(out, null);
    }

    /**
     * @param out     buffered connection output stream (headers and in-memory bodies)
     * @param channel the same connection as a channel for zero-copy file sends,
     *                or {@code null} when zero-copy must not be used (for example TLS)
     */
    public HttpResponse(OutputStream out, WritableByteChannel channel) {
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out);
        this.channel = channel;
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
        this.filePath = null;
        headers.putIfAbsent("Content-Type", "text/plain; charset=" + charset.name().toLowerCase(Locale.ROOT));
        return this;
    }

    public HttpResponse json(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        this.filePath = null;
        headers.putIfAbsent("Content-Type", "application/json; charset=utf-8");
        return this;
    }

    public HttpResponse html(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        this.filePath = null;
        headers.putIfAbsent("Content-Type", "text/html; charset=utf-8");
        return this;
    }

    public HttpResponse bytes(byte[] data, String contentType) {
        this.body = data == null ? new byte[0] : data;
        if (contentType != null) {
        this.filePath = null;
            headers.put("Content-Type", contentType);
        }
        return this;
    }

    /** Convenience empty body with status (e.g. 204). */
    public HttpResponse empty() {
        this.body = new byte[0];
        this.filePath = null;
        return this;
    }

    /**
     * Send {@code length} bytes of {@code file} starting at {@code offset} as the body.
     * The file is opened only when the response is finished, so handlers stay cheap.
     */
    public HttpResponse file(Path file, long offset, long length, String contentType) {
        if (file == null || offset < 0 || length < 0) {
            throw new IllegalArgumentException("invalid file region");
        }
        this.body = new byte[0];
        this.filePath = file;
        this.fileOffset = offset;
        this.fileLength = length;
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        return this;
    }

    /** True when the body is a file region rather than in-memory bytes. */
    public boolean hasFileBody() {
        return filePath != null;
    }

    /** True when this connection supports zero-copy file sends. */
    public boolean zeroCopyCapable() {
        return channel != null;
    }

    /** Current response body bytes (may be empty). */
    public byte[] body() {
        return body;
    }

    /** Replace the buffered body (used by compression middleware). */
    public HttpResponse body(byte[] data) {
        this.body = data == null ? new byte[0] : data;
        this.filePath = null;
        return this;
    }

    /**
     * Negotiate {@code Content-Encoding} from the request {@code Accept-Encoding}.
     * Supports {@code gzip} via JDK {@link GZIPOutputStream}. Brotli ({@code br}) is
     * not offered: the JDK has no pure-Java Brotli encoder without native/third-party deps.
     *
     * <p>No-ops when already committed, empty/tiny bodies, non-compressible types,
     * error-ish empty statuses, or when {@code Content-Encoding} is already set.
     *
     * @return {@code true} when the body was compressed
     */
    public boolean negotiateContentEncoding(HttpRequest req) {
        if (committed || filePath != null || body.length < 256) {
            return false;
        }
        if (status == 204 || status == 304 || status == 206) {
            return false;
        }
        if (headerValue("Content-Encoding") != null) {
            return false;
        }
        String ct = headerValue("Content-Type");
        if (!isCompressible(ct)) {
            return false;
        }
        String ae = req == null ? "" : req.header("accept-encoding").orElse("");
        if (!acceptsGzip(ae)) {
            return false;
        }
        try {
            byte[] compressed = gzip(body);
            // Only keep gzip when it actually shrinks the payload.
            if (compressed.length >= body.length) {
                addVaryAcceptEncoding();
                return false;
            }
            this.body = compressed;
            headers.put("Content-Encoding", "gzip");
            // The gzip bytes differ from identity, so the validator must differ too.
            String etag = headerValue("ETag");
            if (etag != null && !etag.endsWith("-gzip\"")) {
                String bare = etag.startsWith("W/") ? etag.substring(2) : etag;
                if (bare.length() >= 2 && bare.endsWith("\"")) {
                    headers.put("ETag", "W/" + bare.substring(0, bare.length() - 1) + "-gzip\"");
                }
            }
            addVaryAcceptEncoding();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void addVaryAcceptEncoding() {
        String vary = headerValue("Vary");
        if (vary == null || vary.isEmpty()) {
            headers.put("Vary", "Accept-Encoding");
        } else if (!vary.toLowerCase(Locale.ROOT).contains("accept-encoding")) {
            headers.put("Vary", vary + ", Accept-Encoding");
        }
    }

    private String headerValue(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return false;
        }
        String lower = acceptEncoding.toLowerCase(Locale.ROOT);
        // Respect explicit gzip;q=0
        for (String part : lower.split(",")) {
            String p = part.trim();
            if (!p.startsWith("gzip")) {
                continue;
            }
            int q = p.indexOf(";q=");
            if (q >= 0) {
                try {
                    double quality = Double.parseDouble(p.substring(q + 3).trim());
                    return quality > 0.0;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    static boolean isCompressible(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        if (ct.startsWith("text/")) {
            return true;
        }
        return ct.equals("application/json")
                || ct.equals("application/javascript")
                || ct.equals("application/xml")
                || ct.equals("image/svg+xml")
                || ct.endsWith("+json")
                || ct.endsWith("+xml");
    }

    static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(input);
        }
        return bos.toByteArray();
    }


    /**
     * Commit headers for a {@code text/event-stream} body and leave the connection
     * open for {@link #writeStream(byte[])} calls. Sets {@code Connection: close}
     * so the client sees EOF when the stream ends. Idempotent finish is a no-op
     * afterwards; the server closes the socket instead of keep-alive reuse.
     */
    public void startEventStream() throws IOException {
        if (committed) {
            throw new IllegalStateException("Response already committed");
        }
        this.status = 200;
        this.filePath = null;
        this.body = new byte[0];
        headers.put("Content-Type", "text/event-stream; charset=utf-8");
        headers.putIfAbsent("Cache-Control", "no-cache");
        headers.put("Connection", "close");
        headers.remove("Content-Length");
        writeHeadersWithoutLength();
        committed = true;
        streaming = true;
        closeConnection = true;
    }

    /** True while an SSE (or other early-commit) stream is active. */
    public boolean streaming() {
        return streaming;
    }

    /** True when the server should close the socket after this response. */
    public boolean closeConnection() {
        return closeConnection;
    }

    /**
     * Body bytes sent so far: streamed writes, or the finished Content-Length body
     * (file region length or in-memory body). Useful for access logs.
     */
    public long bytesWritten() {
        if (streaming || bytesWritten > 0) {
            return bytesWritten;
        }
        if (filePath != null) {
            return fileLength;
        }
        return body == null ? 0L : body.length;
    }

    /** Write raw body bytes on a committed stream (SSE). Flushes. */
    public void writeStream(byte[] data) throws IOException {
        if (!streaming) {
            throw new IllegalStateException("Response is not streaming");
        }
        if (data == null || data.length == 0) {
            return;
        }
        out.write(data);
        out.flush();
        bytesWritten += data.length;
    }

    public void flushStream() throws IOException {
        if (!streaming) {
            throw new IllegalStateException("Response is not streaming");
        }
        out.flush();
    }

    /** End an SSE stream (flush). Safe to call more than once. */
    public void endEventStream() throws IOException {
        if (!streaming) {
            return;
        }
        out.flush();
        streaming = false;
    }

    private void writeHeadersWithoutLength() throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (isSetCookie(e.getKey())) {
                continue;
            }
            if ("content-length".equalsIgnoreCase(e.getKey())) {
                continue;
            }
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        for (String c : setCookies) {
            sb.append("Set-Cookie: ").append(c).append("\r\n");
        }
        sb.append("\r\n");
        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        // Streamed responses always use the OutputStream path so we never mix
        // BufferedOutputStream writes with SocketChannel on the same socket.
        out.write(headerBytes);
        out.flush();
    }
    public void finish() throws IOException {
        finish(null);
    }

    public void finish(HttpMethod method) throws IOException {
        if (committed) {
            if (streaming) {
                try {
                    out.flush();
                } catch (IOException ignored) {
                    // best effort on teardown
                }
            }
            return;
        }
        committed = true;
        boolean head = suppressBody || method == HttpMethod.HEAD;
        long contentLength = filePath != null ? fileLength : body.length;
        headers.put("Content-Length", Long.toString(contentLength));
        if (!head) {
            bytesWritten = contentLength;
        }
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
        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        // When a SocketChannel is available, send the whole response through it.
        // Mixing BufferedOutputStream and SocketChannel on the same Windows socket
        // can stall keep-alive clients; see writeFileRegion().
        if (channel != null) {
            if (!head && filePath == null && body.length > 0) {
                // Header + body in one gathering write: one syscall, one TCP segment for small bodies.
                writeFully(channel, ByteBuffer.wrap(headerBytes), ByteBuffer.wrap(body));
            } else {
                writeFully(channel, ByteBuffer.wrap(headerBytes));
                if (!head && filePath != null) {
                    writeFileRegion();
                }
            }
        } else {
            out.write(headerBytes);
            if (!head && filePath != null) {
                writeFileRegion();
            } else if (!head && body.length > 0) {
                out.write(body);
            }
            out.flush();
        }
    }

    private static void writeFully(WritableByteChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    private static void writeFully(WritableByteChannel ch, ByteBuffer a, ByteBuffer b) throws IOException {
        if (ch instanceof GatheringByteChannel g) {
            ByteBuffer[] bufs = {a, b};
            while (b.hasRemaining()) {
                g.write(bufs);
            }
        } else {
            writeFully(ch, a);
            writeFully(ch, b);
        }
    }

    /**
     * Send the pending file region. Headers sit in the buffered stream, so flush them
     * first; then prefer {@link FileChannel#transferTo} (zero-copy) and fall back to a
     * pooled-buffer copy when no channel is available or the OS transfers nothing.
     */
    private void writeFileRegion() throws IOException {
        long pos = fileOffset;
        long remaining = fileLength;
        try (FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)) {
            if (channel != null) {
                long zeroCopied = 0L;
                while (remaining > 0) {
                    long n = fc.transferTo(pos, remaining, channel);
                    if (n <= 0) {
                        break;
                    }
                    pos += n;
                    remaining -= n;
                    zeroCopied += n;
                }
                if (zeroCopied > 0) {
                    Trace.METRICS.recordZeroCopy(zeroCopied);
                }
                long streamed = 0L;
                while (remaining > 0) {
                    ByteBuffer buf = ByteBuffer.allocateDirect(
                            (int) Math.min(64 * 1024L, Math.max(1L, remaining)));
                    if (remaining < buf.capacity()) {
                        buf.limit((int) remaining);
                    }
                    int n = fc.read(buf, pos);
                    if (n <= 0) {
                        throw new IOException("file shrank while sending: " + filePath);
                    }
                    buf.flip();
                    writeFully(channel, buf);
                    pos += n;
                    remaining -= n;
                    streamed += n;
                }
                if (streamed > 0) {
                    Trace.METRICS.recordStreamedFile(streamed);
                }
            } else {
                // TLS / tests: stream through the buffered OutputStream.
                out.flush();
                byte[] buf = new byte[(int) Math.min(64 * 1024L, Math.max(1L, remaining))];
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    // FileChannel for positioned reads even on the stream path.
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, want);
                    int n = fc.read(bb, pos);
                    if (n <= 0) {
                        throw new IOException("file shrank while sending: " + filePath);
                    }
                    out.write(buf, 0, n);
                    pos += n;
                    remaining -= n;
                }
                Trace.METRICS.recordStreamedFile(fileLength);
            }
        }
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
