package io.github.glint.http;

import java.util.Locale;

/** Minimal MIME map for dashboard and static assets. */
public final class MimeTypes {

    private MimeTypes() {
    }

    public static String forPath(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html") || p.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (p.endsWith(".js") || p.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        }
        if (p.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (p.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (p.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".gif")) {
            return "image/gif";
        }
        if (p.endsWith(".webp")) {
            return "image/webp";
        }
        if (p.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (p.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (p.endsWith(".woff")) {
            return "font/woff";
        }
        if (p.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (p.endsWith(".map")) {
            return "application/json; charset=utf-8";
        }
        if (p.endsWith(".txt") || p.endsWith(".log")) {
            return "text/plain; charset=utf-8";
        }
        if (p.endsWith(".wasm")) {
            return "application/wasm";
        }
        if (p.endsWith(".xml")) {
            return "application/xml; charset=utf-8";
        }
        if (p.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }
}