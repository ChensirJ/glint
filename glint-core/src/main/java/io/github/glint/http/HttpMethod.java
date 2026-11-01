package io.github.glint.http;

/** Supported HTTP methods for the Glint router. */
public enum HttpMethod {
    GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH;

    public static HttpMethod from(String raw) {
        return HttpMethod.valueOf(raw.trim().toUpperCase());
    }

    /**
     * Match an ASCII method token without allocating. Case-insensitive.
     * Returns {@code null} when the token is not a known method.
     */
    static HttpMethod fromAscii(byte[] raw, int off, int len) {
        if (raw == null || len <= 0 || off < 0 || off + len > raw.length) {
            return null;
        }
        switch (len) {
            case 3:
                if (eq3(raw, off, 'G', 'E', 'T')) {
                    return GET;
                }
                if (eq3(raw, off, 'P', 'U', 'T')) {
                    return PUT;
                }
                return null;
            case 4:
                if (eq4(raw, off, 'P', 'O', 'S', 'T')) {
                    return POST;
                }
                if (eq4(raw, off, 'H', 'E', 'A', 'D')) {
                    return HEAD;
                }
                return null;
            case 5:
                if (eq5(raw, off, 'P', 'A', 'T', 'C', 'H')) {
                    return PATCH;
                }
                return null;
            case 6:
                if (eq6(raw, off, 'D', 'E', 'L', 'E', 'T', 'E')) {
                    return DELETE;
                }
                return null;
            case 7:
                if (eq7(raw, off, 'O', 'P', 'T', 'I', 'O', 'N', 'S')) {
                    return OPTIONS;
                }
                return null;
            default:
                return null;
        }
    }

    private static int up(byte b) {
        int c = b & 0xff;
        return (c >= 'a' && c <= 'z') ? c - 32 : c;
    }

    private static boolean eq3(byte[] raw, int off, char a, char b, char c) {
        return up(raw[off]) == a && up(raw[off + 1]) == b && up(raw[off + 2]) == c;
    }

    private static boolean eq4(byte[] raw, int off, char a, char b, char c, char d) {
        return eq3(raw, off, a, b, c) && up(raw[off + 3]) == d;
    }

    private static boolean eq5(byte[] raw, int off, char a, char b, char c, char d, char e) {
        return eq4(raw, off, a, b, c, d) && up(raw[off + 4]) == e;
    }

    private static boolean eq6(byte[] raw, int off, char a, char b, char c, char d, char e, char f) {
        return eq5(raw, off, a, b, c, d, e) && up(raw[off + 5]) == f;
    }

    private static boolean eq7(byte[] raw, int off, char a, char b, char c, char d, char e, char f, char g) {
        return eq6(raw, off, a, b, c, d, e, f) && up(raw[off + 6]) == g;
    }
}
