package io.github.glint.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 request parser.
 * Supports request-line, headers, and {@code Content-Length} bodies.
 * Chunked transfer encoding is out of scope for 0.1.
 */
public final class HttpParser {

    private HttpParser() {
    }

    /**
     * Parse the next request from {@code in}.
     *
     * @return the request, or {@code null} if the peer closed before sending a request-line
     */
    public static HttpRequest parse(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null) {
            return null;
        }
        if (requestLine.isEmpty()) {
            // Skip blank lines that some clients send between keep-alive requests.
            requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return null;
            }
        }

        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed request line: " + requestLine);
        }
        HttpMethod method = HttpMethod.from(parts[0]);
        String target = parts[1];

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException("Unexpected EOF in headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }

        int contentLength = 0;
        String cl = headers.get("content-length");
        if (cl != null && !cl.isEmpty()) {
            contentLength = Integer.parseInt(cl.trim());
            if (contentLength < 0) {
                throw new IOException("Negative Content-Length");
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

        return new HttpRequest(method, target, headers, body);
    }

    /** Read a single CRLF- or LF-terminated line (without the terminator). */
    static String readLine(InputStream in) throws IOException {
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
            if (sb.length() > 8192) {
                throw new IOException("Header line too long");
            }
        }
        return sb.toString();
    }

    /** Package-visible helper for tests: parse from a UTF-8 string. */
    public static HttpRequest parseString(String raw) throws IOException {
        return parse(new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
