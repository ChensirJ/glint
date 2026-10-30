package io.github.glint.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP/1.1 request parser.
 * Supports request-line, headers, {@code Content-Length} bodies, and
 * chunked transfer encoding for request bodies.
 *
 * <p>Headers are parsed eagerly; the body is exposed as a stream on
 * {@link HttpRequest} so large uploads need not be buffered. Size limits from
 * {@link ServerConfig} still apply ({@code Content-Length} over the limit is
 * rejected before the handler runs; chunked bodies are limited while reading).
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

        return openBody(method, target, headers, in, config);
    }

    /**
     * Attach a streaming body source. Does not read body bytes yet (except
     * rejecting an oversized Content-Length).
     */
    static HttpRequest openBody(HttpMethod method, String target, Map<String, String> headers,
                                InputStream in, ServerConfig config) {
        String te = headers.getOrDefault("transfer-encoding", "");
        if (te.toLowerCase(Locale.ROOT).contains("chunked")) {
            InputStream body = new ChunkedInputStream(in, config.maxRequestBodyBytes());
            return new HttpRequest(method, target, headers, -1L, body);
        }
        String cl = headers.get("content-length");
        if (cl != null && !cl.isEmpty()) {
            final long contentLength;
            try {
                contentLength = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Invalid Content-Length");
            }
            if (contentLength < 0) {
                throw new HttpException(400, "Negative Content-Length");
            }
            if (contentLength > config.maxRequestBodyBytes()) {
                throw new HttpException(413, "Request body too large");
            }
            InputStream body = new ContentLengthInputStream(in, contentLength);
            return new HttpRequest(method, target, headers, contentLength, body);
        }
        return new HttpRequest(method, target, headers, 0L, InputStream.nullInputStream());
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