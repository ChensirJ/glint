package io.github.glint.http;

/** Supported HTTP methods for the Glint router. */
public enum HttpMethod {
    GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH;

    public static HttpMethod from(String raw) {
        return HttpMethod.valueOf(raw.trim().toUpperCase());
    }
}
