package io.github.glint.http;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed HTTP/1.1 request view.
 * Path parameters are filled in by the router after matching.
 * Query parameters are decoded lazily from the raw query string.
 */
public final class HttpRequest {

    private final HttpMethod method;
    private final String rawPath;
    private final String path;
    private final String query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Map<String, String> pathParams = new LinkedHashMap<>();
    private Map<String, String> queryParams;

    public HttpRequest(HttpMethod method, String rawTarget, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.rawPath = rawTarget;
        int q = rawTarget.indexOf('?');
        if (q >= 0) {
            this.path = decodePath(rawTarget.substring(0, q));
            this.query = rawTarget.substring(q + 1);
        } else {
            this.path = decodePath(rawTarget);
            this.query = "";
        }
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? new byte[0] : body;
    }

    private static String decodePath(String p) {
        // Keep path decoding conservative: only '+' stays literal in paths.
        try {
            return URLDecoder.decode(p.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return p;
        }
    }

    public HttpMethod method() {
        return method;
    }

    /** Path without query string. */
    public String path() {
        return path;
    }

    public String rawTarget() {
        return rawPath;
    }

    public String query() {
        return query;
    }

    /** Decoded query parameters (first value wins for duplicate keys). */
    public Map<String, String> queryParams() {
        if (queryParams == null) {
            queryParams = parseQuery(query);
        }
        return queryParams;
    }

    public Optional<String> queryParam(String name) {
        return Optional.ofNullable(queryParams().get(name));
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Optional<String> header(String name) {
        String v = headers.get(name.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(v);
    }

    public byte[] bodyBytes() {
        return body;
    }

    public String bodyText() {
        return bodyText(StandardCharsets.UTF_8);
    }

    public String bodyText(Charset charset) {
        return new String(body, charset);
    }

    /**
     * Charset from Content-Type when present, otherwise UTF-8.
     */
    public Charset contentCharset() {
        String ct = header("content-type").orElse("");
        int idx = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            String raw = ct.substring(idx + 8).trim();
            int semi = raw.indexOf(';');
            if (semi >= 0) {
                raw = raw.substring(0, semi).trim();
            }
            raw = raw.replace("\"", "");
            try {
                return Charset.forName(raw);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return StandardCharsets.UTF_8;
    }

    public boolean keepAlive() {
        String connection = header("connection").orElse("").toLowerCase(Locale.ROOT);
        if (connection.contains("close")) {
            return false;
        }
        return true;
    }

    public void pathParam(String name, String value) {
        pathParams.put(name, value);
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public Map<String, String> pathParams() {
        return Collections.unmodifiableMap(pathParams);
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return Collections.unmodifiableMap(map);
        }
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String k;
            String v;
            if (eq < 0) {
                k = urlDecode(part);
                v = "";
            } else {
                k = urlDecode(part.substring(0, eq));
                v = urlDecode(part.substring(eq + 1));
            }
            map.putIfAbsent(k, v);
        }
        return Collections.unmodifiableMap(map);
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
