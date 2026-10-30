package io.github.glint.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP/1.1 request parser.
 * Supports request-line, headers, {@code Content-Length} bodies, and
 * chunked transfer encoding decode for request bodies.
 *
 * <p>Enforces header-line and body size limits from {@link ServerConfig}.
 */
public final class HttpParser {

    private HttpParser() {
    }

    /**
     * Parse the next request from {@code in} using default size limits.
     *
     * @return the request, or {@code null} if the peer closed before a request-line
     */
    public static HttpRequest parse(InputStream in) throws IOException {
        return parse(in, ServerConfig.defaults());
    }

    public static HttpRequest parse(InputStream in, ServerConfig config) throws IOException {
        String requestLine = readLine(in, config.maxHeaderLineBytes());
        if (requestLine == null) {
            return null;
        }
        if (requestLine.isEmpty()) {
            requestLine = readLine(in, config.maxHeaderLineBytes());
            if (requestLine == null || requestLine.isEmpty()) {
                return null;
            }
        }

        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            throw new HttpException(400, "Malformed request line");
        }
        final HttpMethod method;
        try {
            method = HttpMethod.from(parts[0]);
        } catch (IllegalArgumentException ex) {
            throw new HttpException(400, "Unknown method: " + parts[0]);
        }
        String target = parts[1];
        if (target.isEmpty() || target.charAt(0) != '/') {
            // Allow absolute-form rarely; treat others as bad for simplicity.
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                throw new HttpException(400, "Invalid request target");
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in, config.maxHeaderLineBytes());
            if (line == null) {
                throw new HttpException(400, "Unexpected EOF in headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "Malformed header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            // Combine duplicate headers with comma (HTTP semantics).
            String prev = headers.put(name, value);
            if (prev != null) {
                headers.put(name, prev + ", " + value);
            }
        }

        byte[] body = readBody(in, headers, config);
        return new HttpRequest(method, target, headers, body);
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers, ServerConfig config)
            throws IOException {
        String te = headers.getOrDefault("transfer-encoding", "");
        if (te.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in, config.maxRequestBodyBytes());
        }
        int contentLength = 0;
        String cl = headers.get("content-length");
        if (cl != null && !cl.isEmpty()) {
            try {
                contentLength = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Invalid Content-Length");
            }
            if (contentLength < 0) {
                throw new HttpException(400, "Negative Content-Length");
            }
            if (contentLength > config.maxRequestBodyBytes()) {
                throw new HttpException(413, "Request body too large");
            }
        }
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n < 0) {
                throw new EOFException("Unexpected EOF in body");
            }
            off += n;
        }
        return body;
    }

    /**
     * Decode a chunked body until a zero-size chunk. Trailer headers are discarded.
     */
    static byte[] readChunked(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(8192, maxBytes));
        while (true) {
            String sizeLine = readLine(in, 4096);
            if (sizeLine == null) {
                throw new HttpException(400, "Unexpected EOF in chunk size");
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            final int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Invalid chunk size");
            }
            if (size < 0) {
                throw new HttpException(400, "Negative chunk size");
            }
            if (size == 0) {
                // Consume trailers until empty line.
                while (true) {
                    String trailer = readLine(in, 8192);
                    if (trailer == null || trailer.isEmpty()) {
                        break;
                    }
                }
                break;
            }
            if (buf.size() + size > maxBytes) {
                throw new HttpException(413, "Request body too large");
            }
            byte[] chunk = new byte[size];
            int off = 0;
            while (off < size) {
                int n = in.read(chunk, off, size - off);
                if (n < 0) {
                    throw new EOFException("Unexpected EOF in chunk data");
                }
                off += n;
            }
            buf.write(chunk);
            // Consume trailing CRLF after chunk data.
            int c1 = in.read();
            int c2 = in.read();
            if (c1 == '\r' && c2 == '\n') {
                // ok
            } else if (c1 == '\n') {
                // bare LF — tolerate
            } else if (c1 < 0) {
                throw new EOFException("Unexpected EOF after chunk");
            } else {
                throw new HttpException(400, "Missing CRLF after chunk");
            }
        }
        return buf.toByteArray();
    }

    /** Read a single CRLF- or LF-terminated line (without the terminator). */
    static String readLine(InputStream in, int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (sb.length() == 0 && prev < 0) {
                    return null;
                }
                break;
            }
            if (c == 10) { // LF
                if (prev == 13 && sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                }
                break;
            }
            sb.append((char) c);
            prev = c;
            if (sb.length() > maxLen) {
                throw new HttpException(400, "Header line too long");
            }
        }
        return sb.toString();
    }

    /** Package-visible helper for tests: parse from a UTF-8 string. */
    public static HttpRequest parseString(String raw) throws IOException {
        return parse(new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    public static HttpRequest parseString(String raw, ServerConfig config) throws IOException {
        return parse(new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), config);
    }
}
