package io.github.glint.http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable-ish view of a parsed HTTP/1.1 request.
 * Path parameters are filled in by the router after matching.
 */
public final class HttpRequest {

    private final HttpMethod method;
    private final String rawPath;
    private final String path;
    private final String query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Map<String, String> pathParams = new LinkedHashMap<>();

    public HttpRequest(HttpMethod method, String rawTarget, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.rawPath = rawTarget;
        int q = rawTarget.indexOf('?');
        if (q >= 0) {
            this.path = rawTarget.substring(0, q);
            this.query = rawTarget.substring(q + 1);
        } else {
            this.path = rawTarget;
            this.query = "";
        }
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? new byte[0] : body;
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
        return new String(body, StandardCharsets.UTF_8);
    }

    public boolean keepAlive() {
        String connection = header("connection").orElse("").toLowerCase(Locale.ROOT);
        if (connection.contains("close")) {
            return false;
        }
        // HTTP/1.1 default is keep-alive.
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
}
