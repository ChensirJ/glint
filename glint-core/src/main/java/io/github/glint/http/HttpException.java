package io.github.glint.http;

/**
 * Protocol-level error that the server maps to an HTTP status code.
 * Used for malformed requests (400), oversized bodies (413), etc.
 */
public final class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
