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
        // The connection buffer can scan header lines in place. A generic stream
        // stays byte-at-a-time so a caller can observe that the body was not pulled.
        if (in instanceof ConnectionInputStream) {
            return parseBuffered((ConnectionInputStream) in, config);
        }
        return parseGeneric(in, config);
    }

    private static HttpRequest parseGeneric(InputStream in, ServerConfig config) throws IOException {
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
     * Header parse for a keep-alive connection. One Latin-1 string per token,
     * no regex split, and common header names are reused constants.
     */
    private static HttpRequest parseBuffered(ConnectionInputStream in, ServerConfig config) throws IOException {
        int max = config.maxHeaderLineBytes();
        int n = in.readHeaderLine(max);
        if (n < 0) {
            return null;
        }
        if (n == 0) {
            n = in.readHeaderLine(max);
            if (n <= 0) {
                return null;
            }
        }
        byte[] line = in.headerLineBuffer();
        int sp1 = indexOf(line, n, (byte) ' ', 0);
        if (sp1 <= 0) {
            throw new HttpException(400, "Malformed request line");
        }
        int m0 = 0;
        int m1 = sp1;
        while (m0 < m1 && isWs(line[m0])) {
            m0++;
        }
        while (m1 > m0 && isWs(line[m1 - 1])) {
            m1--;
        }
        HttpMethod method = HttpMethod.fromAscii(line, m0, m1 - m0);
        if (method == null) {
            throw new HttpException(400, "Unknown method: " + latin1(line, m0, m1 - m0));
        }
        int sp2 = indexOf(line, n, (byte) ' ', sp1 + 1);
        int t0 = sp1 + 1;
        int t1 = sp2 < 0 ? n : sp2;
        if (t0 >= t1) {
            throw new HttpException(400, "Invalid request target");
        }
        String target = latin1(line, t0, t1 - t0);
        if (target.charAt(0) != '/') {
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                throw new HttpException(400, "Invalid request target");
            }
        }

        Map<String, String> headers = new LinkedHashMap<>(8);
        while (true) {
            n = in.readHeaderLine(max);
            if (n < 0) {
                throw new HttpException(400, "Unexpected EOF in headers");
            }
            if (n == 0) {
                break;
            }
            line = in.headerLineBuffer();
            int colon = indexOf(line, n, (byte) ':', 0);
            if (colon <= 0) {
                throw new HttpException(400, "Malformed header");
            }
            int ns = 0;
            int ne = colon;
            while (ns < ne && isWs(line[ns])) {
                ns++;
            }
            while (ne > ns && isWs(line[ne - 1])) {
                ne--;
            }
            if (ns == ne) {
                throw new HttpException(400, "Malformed header");
            }
            asciiLowerInPlace(line, ns, ne);
            String name = internHeaderName(line, ns, ne - ns);
            int vs = colon + 1;
            int ve = n;
            while (vs < ve && isWs(line[vs])) {
                vs++;
            }
            while (ve > vs && isWs(line[ve - 1])) {
                ve--;
            }
            String value = internHeaderValue(line, vs, ve - vs);
            String prev = headers.put(name, value);
            if (prev != null) {
                headers.put(name, prev + ", " + value);
            }
        }
        return openBody(method, target, headers, in, config);
    }

    private static int indexOf(byte[] b, int len, byte needle, int from) {
        for (int i = from; i < len; i++) {
            if (b[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWs(byte b) {
        return (b & 0xff) <= ' ';
    }

    private static void asciiLowerInPlace(byte[] b, int off, int end) {
        for (int i = off; i < end; i++) {
            int c = b[i] & 0xff;
            if (c >= 'A' && c <= 'Z') {
                b[i] = (byte) (c + 32);
            }
        }
    }

    private static String latin1(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean eqAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            if ((b[off + i] & 0xff) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String internHeaderName(byte[] b, int off, int len) {
        if (len == 4 && eqAscii(b, off, "host")) {
            return "host";
        }
        if (len == 10 && eqAscii(b, off, "connection")) {
            return "connection";
        }
        if (len == 14 && eqAscii(b, off, "content-length")) {
            return "content-length";
        }
        if (len == 12 && eqAscii(b, off, "content-type")) {
            return "content-type";
        }
        if (len == 15 && eqAscii(b, off, "accept-encoding")) {
            return "accept-encoding";
        }
        if (len == 17 && eqAscii(b, off, "transfer-encoding")) {
            return "transfer-encoding";
        }
        if (len == 6 && eqAscii(b, off, "accept")) {
            return "accept";
        }
        if (len == 10 && eqAscii(b, off, "user-agent")) {
            return "user-agent";
        }
        if (len == 5 && eqAscii(b, off, "range")) {
            return "range";
        }
        if (len == 13 && eqAscii(b, off, "if-none-match")) {
            return "if-none-match";
        }
        if (len == 17 && eqAscii(b, off, "if-modified-since")) {
            return "if-modified-since";
        }
        return latin1(b, off, len);
    }

    private static String internHeaderValue(byte[] b, int off, int len) {
        if (len == 8 && eqAscii(b, off, "identity")) {
            return "identity";
        }
        if (len == 10 && eqAscii(b, off, "keep-alive")) {
            return "keep-alive";
        }
        if (len == 5 && eqAscii(b, off, "close")) {
            return "close";
        }
        if (len == 7 && eqAscii(b, off, "chunked")) {
            return "chunked";
        }
        if (len == 9 && eqAscii(b, off, "localhost")) {
            return "localhost";
        }
        if (len == 9 && eqAscii(b, off, "127.0.0.1")) {
            return "127.0.0.1";
        }
        return latin1(b, off, len);
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
        return new HttpRequest(method, target, headers, 0L, HttpRequest.EMPTY_BODY);
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