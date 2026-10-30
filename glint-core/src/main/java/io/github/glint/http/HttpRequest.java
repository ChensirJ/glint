package io.github.glint.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import io.github.glint.http.multipart.MultipartForm;
import io.github.glint.http.multipart.MultipartParser;
import java.util.Optional;

/**
 * Parsed HTTP/1.1 request view.
 * Path parameters are filled in by the router after matching.
 * Query parameters are decoded lazily from the raw query string.
 *
 * <p>Request bodies are streamed by default. Prefer {@link #bodyStream()} or
 * {@link #bodyChannel()} for large uploads. {@link #bodyBytes()} and
 * {@link #bodyText()} still buffer the whole body for small payloads and for
 * callers such as multipart parsing.
 */
public final class HttpRequest {

    private final HttpMethod method;
    private final String rawPath;
    private final String path;
    private final String query;
    private final Map<String, String> headers;
    /** Declared Content-Length, or -1 when chunked / absent. */
    private final long declaredContentLength;
    private final InputStream bodyIn;
    private final Map<String, String> pathParams = new LinkedHashMap<>();
    private Map<String, String> queryParams;
    private byte[] cachedBody;
    private boolean streamOpened;

    /**
     * Construct a request whose body is already fully buffered (tests and
     * in-memory adapters).
     */
    public HttpRequest(HttpMethod method, String rawTarget, Map<String, String> headers, byte[] body) {
        this(method, rawTarget, headers,
                body == null ? 0L : body.length,
                null,
                body == null ? new byte[0] : body);
    }

    /**
     * Construct a request whose body is read on demand from {@code bodyIn}.
     * Package-private: used by {@link HttpParser}.
     *
     * @param declaredContentLength Content-Length value, or -1 if chunked / unknown
     */
    HttpRequest(HttpMethod method, String rawTarget, Map<String, String> headers,
                long declaredContentLength, InputStream bodyIn) {
        this(method, rawTarget, headers, declaredContentLength, bodyIn, null);
    }

    private HttpRequest(HttpMethod method, String rawTarget, Map<String, String> headers,
                        long declaredContentLength, InputStream bodyIn, byte[] cached) {
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
        this.declaredContentLength = declaredContentLength;
        this.cachedBody = cached;
        if (cached != null) {
            this.bodyIn = new ByteArrayInputStream(cached);
        } else if (bodyIn != null) {
            this.bodyIn = bodyIn;
        } else {
            this.bodyIn = InputStream.nullInputStream();
        }
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

    /**
     * Declared {@code Content-Length}, or {@code -1} when the body is chunked
     * or no length was advertised.
     */
    public long contentLength() {
        return declaredContentLength;
    }

    /**
     * Stream the request body without buffering it all in memory.
     * Closing the stream does not close the connection. Do not mix with
     * {@link #bodyBytes()} after bytes have already been read from this stream.
     */
    public InputStream bodyStream() {
        if (cachedBody != null) {
            return new ByteArrayInputStream(cachedBody);
        }
        streamOpened = true;
        return bodyIn;
    }

    /**
     * Channel view of {@link #bodyStream()}. Closing the channel does not close
     * the connection.
     */
    public ReadableByteChannel bodyChannel() {
        return Channels.newChannel(bodyStream());
    }

    /**
     * Buffer the entire body into a byte array (lazy). Prefer
     * {@link #bodyStream()} for large uploads.
     *
     * @throws IllegalStateException if {@link #bodyStream()} was already read from
     */
    public byte[] bodyBytes() {
        if (cachedBody != null) {
            return cachedBody;
        }
        if (streamOpened) {
            throw new IllegalStateException(
                    "Request body is already being streamed; use bodyStream() only, or call bodyBytes() first");
        }
        try {
            cachedBody = bodyIn.readAllBytes();
        } catch (HttpException he) {
            throw he;
        } catch (IOException e) {
            throw new HttpException(400, "Failed to read request body: " + e.getMessage());
        }
        return cachedBody;
    }

    public String bodyText() {
        return bodyText(StandardCharsets.UTF_8);
    }

    public String bodyText(Charset charset) {
        return new String(bodyBytes(), charset);
    }

    /**
     * Discard any unread body bytes so the next keep-alive request can be parsed.
     * No-op when the body was fully read or buffered.
     */
    void drainBody() throws IOException {
        if (cachedBody != null) {
            return;
        }
        if (bodyIn instanceof ContentLengthInputStream cl) {
            cl.drain();
        } else if (bodyIn instanceof ChunkedInputStream ch) {
            ch.drain();
        } else {
            byte[] buf = new byte[8192];
            while (bodyIn.read(buf) >= 0) {
                // discard
            }
        }
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

    /**
     * Parse {@code multipart/form-data} using {@link ServerConfig#defaults()}.
     * Returns an empty form when Content-Type is not multipart.
     * Buffers the body via {@link #bodyBytes()}.
     */
    public MultipartForm multipart() {
        return MultipartParser.parse(this, ServerConfig.defaults());
    }

    /** Parse multipart with custom size limits. */
    public MultipartForm multipart(ServerConfig config) {
        return MultipartParser.parse(this, config);
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