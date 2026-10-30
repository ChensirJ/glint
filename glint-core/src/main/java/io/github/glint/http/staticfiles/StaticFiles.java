package io.github.glint.http.staticfiles;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.http.MimeTypes;
import io.github.glint.router.RouteHandler;
import io.github.glint.router.Router;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Static asset mounts for directory roots and classpath folders.
 *
 * <p>Features: MIME mapping, HEAD (via GET fallback), basic single-range
 * {@code bytes=} support, path-traversal protection, weak {@code ETag},
 * {@code Last-Modified}, and conditional {@code 304 Not Modified}
 * ({@code If-None-Match} / {@code If-Modified-Since}).
 */
public final class StaticFiles {

    private StaticFiles() {
    }

    /**
     * Mount a filesystem directory under {@code urlPrefix}.
     * Example: {@code StaticFiles.mountDirectory(router, "/assets", Path.of("public"))}
     * serves {@code public/app.js} at {@code /assets/app.js}.
     */
    public static void mountDirectory(Router router, String urlPrefix, Path directory) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(directory, "directory");
        String prefix = normalizePrefix(urlPrefix);
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        RouteHandler handler = (req, resp) -> servePath(req, resp, resolveUnderRoot(root, relativePath(req, prefix)));
        register(router, prefix, handler);
    }

    /**
     * Mount a classpath resource directory under {@code urlPrefix}.
     * {@code classpathRoot} uses slash separators without a leading slash
     * (example: {@code "demo/static"}).
     */
    public static void mountClasspath(Router router, String urlPrefix, String classpathRoot) {
        Objects.requireNonNull(router, "router");
        String root = normalizeClasspathRoot(classpathRoot);
        String prefix = normalizePrefix(urlPrefix);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = StaticFiles.class.getClassLoader();
        }
        ClassLoader loader = cl;
        RouteHandler handler = (req, resp) -> {
            String rel = relativePath(req, prefix);
            if (rel.contains("..")) {
                resp.status(403).text("Forbidden");
                return;
            }
            String resource = root.isEmpty() ? rel : (root + "/" + rel);
            resource = resource.replace('\\', '/');
            while (resource.startsWith("/")) {
                resource = resource.substring(1);
            }
            if (resource.isEmpty() || resource.endsWith("/")) {
                resource = resource + "index.html";
            }
            URL url = loader.getResource(resource);
            if (url == null) {
                resp.status(404).text("Not Found");
                return;
            }
            // Reject jar entries that escape via crafted names (defense in depth).
            String path = url.getPath();
            if (path != null && path.contains("..")) {
                resp.status(403).text("Forbidden");
                return;
            }
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    resp.status(404).text("Not Found");
                    return;
                }
                byte[] data = in.readAllBytes();
                writeBytes(req, resp, data, MimeTypes.forPath(resource), resource,
                        contentHashEtag(data), null);
            }
        };
        register(router, prefix, handler);
    }

    private static void register(Router router, String prefix, RouteHandler handler) {
        // Exact prefix (directory index) + greedy children.
        router.add(HttpMethod.GET, prefix, (req, resp) -> {
            req.pathParam("path", "");
            handler.handle(req, resp);
        });
        if (!prefix.equals("/")) {
            router.add(HttpMethod.GET, prefix + "/", (req, resp) -> {
                req.pathParam("path", "");
                handler.handle(req, resp);
            });
        }
        String catchAll = prefix.equals("/") ? "/{*path}" : prefix + "/{*path}";
        router.add(HttpMethod.GET, catchAll, handler);
    }

    private static void servePath(HttpRequest req, HttpResponse resp, Optional<Path> fileOpt) throws Exception {
        if (fileOpt.isEmpty()) {
            resp.status(403).text("Forbidden");
            return;
        }
        Path file = fileOpt.get();
        if (Files.isDirectory(file)) {
            Path index = file.resolve("index.html").normalize();
            if (!index.startsWith(file) || !Files.isRegularFile(index)) {
                resp.status(404).text("Not Found");
                return;
            }
            file = index;
        }
        if (!Files.isRegularFile(file)) {
            resp.status(404).text("Not Found");
            return;
        }
        byte[] data = Files.readAllBytes(file);
        FileTime mtime = Files.getLastModifiedTime(file);
        String etag = weakEtag(data.length, mtime.toMillis());
        writeBytes(req, resp, data, MimeTypes.forPath(file.getFileName().toString()),
                file.getFileName().toString(), etag, mtime.toInstant());
    }

    static void writeBytes(HttpRequest req, HttpResponse resp, byte[] data, String contentType, String name)
            throws IOException {
        writeBytes(req, resp, data, contentType, name, contentHashEtag(data), null);
    }

    static void writeBytes(HttpRequest req, HttpResponse resp, byte[] data, String contentType, String name,
                           String etag, Instant lastModified)
            throws IOException {
        resp.header("Accept-Ranges", "bytes");
        resp.header("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        if (etag != null && !etag.isEmpty()) {
            resp.header("ETag", etag);
        }
        if (lastModified != null) {
            resp.header("Last-Modified", formatHttpDate(lastModified));
        }

        if (isNotModified(req, etag, lastModified)) {
            resp.status(304).empty();
            return;
        }

        Optional<String> rangeHeader = req.header("range");
        if (rangeHeader.isPresent() && req.method() == HttpMethod.GET) {
            Range range = parseRange(rangeHeader.get(), data.length);
            if (range == null) {
                // Ignore malformed Range; serve full body.
                resp.status(200).bytes(data, contentType);
                return;
            }
            if (!range.satisfiable()) {
                resp.status(416)
                        .header("Content-Range", "bytes */" + data.length)
                        .empty();
                return;
            }
            byte[] slice = new byte[range.length()];
            System.arraycopy(data, range.start(), slice, 0, range.length());
            resp.status(206)
                    .header("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + data.length)
                    .bytes(slice, contentType);
            return;
        }
        resp.status(200).bytes(data, contentType);
    }

    /** Weak ETag from size + mtime (filesystem mounts). */
    static String weakEtag(long size, long mtimeMillis) {
        return "W/\"" + Long.toHexString(size) + "-" + Long.toHexString(mtimeMillis) + "\"";
    }

    /** Strong-ish ETag from SHA-256 of content (classpath mounts). */
    static String contentHashEtag(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data == null ? new byte[0] : data);
            StringBuilder sb = new StringBuilder(dig.length * 2 + 2);
            sb.append('"');
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            sb.append('"');
            return sb.toString();
        } catch (Exception e) {
            return "W/\"" + (data == null ? 0 : data.length) + "\"";
        }
    }

    static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    static String formatHttpDate(Instant instant) {
        return HTTP_DATE.format(instant);
    }

    /**
     * RFC 9110 conditional GET: prefer {@code If-None-Match}; else {@code If-Modified-Since}.
     */
    static boolean isNotModified(HttpRequest req, String etag, Instant lastModified) {
        Optional<String> inm = req.header("if-none-match");
        if (inm.isPresent() && etag != null) {
            return etagMatches(inm.get(), etag);
        }
        if (lastModified == null) {
            return false;
        }
        Optional<String> ims = req.header("if-modified-since");
        if (ims.isEmpty()) {
            return false;
        }
        Instant since = parseHttpDate(ims.get());
        if (since == null) {
            return false;
        }
        // Compare at second resolution (HTTP-date has no sub-second precision).
        long fileSec = lastModified.getEpochSecond();
        long sinceSec = since.getEpochSecond();
        return fileSec <= sinceSec;
    }

    static boolean etagMatches(String ifNoneMatch, String etag) {
        String hay = ifNoneMatch.trim();
        if ("*".equals(hay)) {
            return true;
        }
        String target = stripWeakPrefix(etag);
        for (String part : hay.split(",")) {
            String cand = stripWeakPrefix(part.trim());
            if (cand.equals(target) || part.trim().equals(etag)) {
                return true;
            }
        }
        return false;
    }

    static String stripWeakPrefix(String tag) {
        String t = tag.trim();
        if (t.regionMatches(true, 0, "W/", 0, 2)) {
            t = t.substring(2).trim();
        }
        return t;
    }

    static Instant parseHttpDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.from(HTTP_DATE.parse(value.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve {@code relative} under {@code root}; empty optional means traversal / missing.
     */
    static Optional<Path> resolveUnderRoot(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        String rel = relative == null ? "" : relative.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.contains("\0")) {
            return Optional.empty();
        }
        // Reject obvious traversal tokens before resolve.
        for (String part : rel.split("/")) {
            if ("..".equals(part)) {
                return Optional.empty();
            }
        }
        Path resolved = rel.isEmpty() ? base : base.resolve(rel).normalize();
        if (!resolved.startsWith(base)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    private static String relativePath(HttpRequest req, String prefix) {
        String fromParam = req.pathParam("path");
        if (fromParam != null) {
            return fromParam;
        }
        String path = req.path();
        if (prefix.equals("/")) {
            return path.startsWith("/") ? path.substring(1) : path;
        }
        if (path.equals(prefix) || path.equals(prefix + "/")) {
            return "";
        }
        if (path.startsWith(prefix + "/")) {
            return path.substring(prefix.length() + 1);
        }
        return "";
    }

    private static String normalizePrefix(String urlPrefix) {
        Objects.requireNonNull(urlPrefix, "urlPrefix");
        String p = urlPrefix.trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("urlPrefix required");
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String normalizeClasspathRoot(String classpathRoot) {
        Objects.requireNonNull(classpathRoot, "classpathRoot");
        String r = classpathRoot.replace('\\', '/').trim();
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        if (r.contains("..")) {
            throw new IllegalArgumentException("classpathRoot must not contain ..");
        }
        return r;
    }

    /** Inclusive byte range. */
    record Range(int start, int end, boolean satisfiable) {
        int length() {
            return end - start + 1;
        }
    }

    /**
     * Parse a single {@code bytes=start-end} range. Multiple ranges are not supported
     * (returns null so the caller serves the full body).
     */
    static Range parseRange(String header, int total) {
        if (header == null || total <= 0) {
            return null;
        }
        String h = header.trim();
        if (!h.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            return null;
        }
        String spec = h.substring(6).trim();
        if (spec.contains(",")) {
            return null; // multi-range not supported
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        try {
            int start;
            int end;
            if (startStr.isEmpty()) {
                // suffix: bytes=-N
                int suffix = Integer.parseInt(endStr);
                if (suffix <= 0) {
                    return new Range(0, 0, false);
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Integer.parseInt(startStr);
                end = endStr.isEmpty() ? total - 1 : Integer.parseInt(endStr);
            }
            if (start < 0 || start >= total) {
                return new Range(0, 0, false);
            }
            if (end < start) {
                return new Range(0, 0, false);
            }
            end = Math.min(end, total - 1);
            return new Range(start, end, true);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}