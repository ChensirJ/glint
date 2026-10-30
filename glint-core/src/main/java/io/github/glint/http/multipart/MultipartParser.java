package io.github.glint.http.multipart;

import io.github.glint.http.HttpException;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.ServerConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code multipart/form-data} request bodies with hard size limits.
 *
 * <p>Limits come from {@link ServerConfig}: max parts, max bytes per part,
 * and overall max request body (already enforced by the HTTP parser).
 *
 * <p>Implementation is intentionally allocation-light and avoids
 * {@code synchronized} so it stays friendly to virtual threads on JDK 21.
 */
public final class MultipartParser {

    private static final Pattern NAME = Pattern.compile(
            "name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile(
            "filename=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    // RFC 5987 / filename* = charset'lang'value (percent-encoded)
    private static final Pattern FILENAME_STAR = Pattern.compile(
            "filename\\*=([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);

    private MultipartParser() {
    }

    /**
     * Parse multipart from a request. Returns empty form when Content-Type
     * is not multipart/form-data.
     */
    public static MultipartForm parse(HttpRequest request) {
        return parse(request, ServerConfig.defaults());
    }

    public static MultipartForm parse(HttpRequest request, ServerConfig config) {
        String ct = request.header("content-type").orElse("");
        String boundary = extractBoundary(ct);
        if (boundary == null) {
            return MultipartForm.empty();
        }
        return parse(request.bodyBytes(), boundary, config);
    }

    /**
     * Parse a raw multipart body with an explicit boundary string
     * (without the leading {@code --}).
     */
    public static MultipartForm parse(byte[] body, String boundary, ServerConfig config) {
        if (body == null || body.length == 0 || boundary == null || boundary.isEmpty()) {
            return MultipartForm.empty();
        }
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] delimCrlf = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);

        List<Part> parts = new ArrayList<>();
        int pos = indexOf(body, delim, 0);
        if (pos < 0) {
            throw new HttpException(400, "Multipart boundary not found");
        }
        pos += delim.length;
        // Optional whitespace / preamble after first boundary is not expected;
        // require CRLF or immediate end marker.
        if (startsWith(body, pos, new byte[]{'-', '-'})) {
            return MultipartForm.empty(); // empty multipart
        }
        if (startsWith(body, pos, new byte[]{'\r', '\n'})) {
            pos += 2;
        } else if (pos < body.length && body[pos] == '\n') {
            pos += 1;
        }

        while (pos < body.length) {
            if (parts.size() >= config.maxMultipartParts()) {
                throw new HttpException(413, "Too many multipart parts");
            }
            int next = indexOf(body, delimCrlf, pos);
            boolean closing = false;
            int partEnd;
            if (next < 0) {
                // Last part may end with --boundary--
                int endMark = indexOf(body, delim, pos);
                if (endMark < 0) {
                    // Tolerate missing final boundary (some clients).
                    partEnd = body.length;
                    next = body.length;
                    closing = true;
                } else {
                    partEnd = endMark;
                    // rewind over preceding CRLF if present
                    if (partEnd >= 2 && body[partEnd - 2] == '\r' && body[partEnd - 1] == '\n') {
                        partEnd -= 2;
                    } else if (partEnd >= 1 && body[partEnd - 1] == '\n') {
                        partEnd -= 1;
                    }
                    next = endMark + delim.length;
                    closing = true;
                }
            } else {
                partEnd = next;
                next = next + delimCrlf.length;
            }

            byte[] partBytes = slice(body, pos, partEnd);
            parts.add(parseOnePart(partBytes, config));

            if (closing) {
                break;
            }
            // After CRLF--boundary we may see -- (close) or CRLF (next part)
            if (startsWith(body, next, new byte[]{'-', '-'})) {
                break;
            }
            if (startsWith(body, next, new byte[]{'\r', '\n'})) {
                pos = next + 2;
            } else if (next < body.length && body[next] == '\n') {
                pos = next + 1;
            } else {
                pos = next;
            }
        }
        return new MultipartForm(parts);
    }

    private static Part parseOnePart(byte[] raw, ServerConfig config) {
        int sep = indexOf(raw, new byte[]{'\r', '\n', '\r', '\n'}, 0);
        int headerEnd;
        int bodyStart;
        if (sep >= 0) {
            headerEnd = sep;
            bodyStart = sep + 4;
        } else {
            int sepLf = indexOf(raw, new byte[]{'\n', '\n'}, 0);
            if (sepLf < 0) {
                throw new HttpException(400, "Malformed multipart part (no header terminator)");
            }
            headerEnd = sepLf;
            bodyStart = sepLf + 2;
        }
        Map<String, String> headers = parsePartHeaders(raw, 0, headerEnd);
        byte[] data = slice(raw, bodyStart, raw.length);
        // Strip trailing CRLF that some encoders leave inside the part slice —
        // already handled by partEnd adjustment; keep data as-is.

        if (data.length > config.maxMultipartPartBytes()) {
            throw new HttpException(413, "Multipart part too large");
        }

        String cd = Part.header(headers, "content-disposition");
        String name = matchGroup(NAME, cd, 1);
        if (name == null) {
            throw new HttpException(400, "Multipart part missing name");
        }
        String filename = matchGroup(FILENAME_STAR, cd, 2);
        if (filename != null) {
            filename = percentDecode(filename.trim());
        } else {
            filename = matchGroup(FILENAME, cd, 1);
        }
        String contentType = Part.header(headers, "content-type");
        return new Part(name, filename, contentType, headers, data);
    }

    private static Map<String, String> parsePartHeaders(byte[] raw, int from, int to) {
        Map<String, String> headers = new LinkedHashMap<>();
        String text = new String(raw, from, to - from, StandardCharsets.US_ASCII);
        String[] lines = text.split("\r\n|\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String n = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(colon + 1).trim();
            String prev = headers.put(n, v);
            if (prev != null) {
                headers.put(n, prev + ", " + v);
            }
        }
        return headers;
    }

    /** Extract boundary token from a Content-Type header value. */
    public static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (!lower.contains("multipart/form-data")) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b.isEmpty() ? null : b;
            }
        }
        return null;
    }

    private static String matchGroup(Pattern p, String input, int group) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Matcher m = p.matcher(input);
        if (m.find()) {
            return m.group(group);
        }
        return null;
    }

    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    out.write(v);
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // fall through and write literally
                }
            }
            out.write((byte) c);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] hay, int pos, byte[] prefix) {
        if (pos + prefix.length > hay.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (hay[pos + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] src, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(src.length, to);
        if (t <= f) {
            return new byte[0];
        }
        byte[] out = new byte[t - f];
        System.arraycopy(src, f, out, 0, out.length);
        return out;
    }
}
