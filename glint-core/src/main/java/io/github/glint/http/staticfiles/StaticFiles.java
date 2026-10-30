package io.github.glint.http.staticfiles;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.http.MimeTypes;
import io.github.glint.obs.Trace;
import io.github.glint.router.RouteHandler;
import io.github.glint.router.Router;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.glint.util.Json;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static asset mounts for directory roots and classpath folders.
 *
 * <p>Features: MIME mapping, HEAD (via GET fallback), basic single-range
 * {@code bytes=} support, path-traversal protection, weak {@code ETag},
 * {@code Last-Modified}, and conditional {@code 304 Not Modified}
 * ({@code If-None-Match} / {@code If-Modified-Since}).
 *
 * <p>Directory mounts additionally provide (see {@link StaticOptions}):
 * an in-memory cache for small files, zero-copy {@code FileChannel.transferTo}
 * for larger files on plain-TCP connections, precompressed {@code .br}/{@code .gz}
 * siblings, and {@code Cache-Control: immutable} for fingerprinted file names.
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
        mountDirectory(router, urlPrefix, directory, StaticOptions.defaults());
    }

    /**
     * Mount a filesystem directory with explicit {@link StaticOptions}
     * (cache size, precompressed siblings, immutable cache headers).
     */
    public static void mountDirectory(Router router, String urlPrefix, Path directory, StaticOptions options) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(directory, "directory");
        StaticOptions opts = options == null ? StaticOptions.defaults() : options;
        String prefix = normalizePrefix(urlPrefix);
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        // One cache per mount so budgets stay predictable.
        StaticFileCache cache = new StaticFileCache(opts.cacheMaxTotalBytes());
        ConcurrentHashMap<String, Resolved> meta = new ConcurrentHashMap<>();
        long revalidateNanos = TimeUnit.MILLISECONDS.toNanos(opts.revalidateMillis());
        RouteHandler handler = (req, resp) -> {
            String rel = relativePath(req, prefix);
            long now = System.nanoTime();
            Resolved r = meta.get(rel);
            if (r == null || revalidateNanos == 0 || now - r.checkedAtNanos() > revalidateNanos) {
                r = resolve(root, rel, opts, now);
                if (meta.size() >= MAX_META_ENTRIES) {
                    meta.clear(); // crude bound; hot entries come back on the next request
                }
                meta.put(rel, r);
            }
            serveResolved(req, resp, r, opts, cache);
        };
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

    /** Upper bound for cached URL-to-file resolutions per mount. */
    private static final int MAX_META_ENTRIES = 10_000;

    /**
     * One on-disk representation (identity, gzip, or br) with its validators
     * precomputed so the hot path does no formatting or filesystem work.
     */
    record Variant(Path path, long size, long mtimeMillis, String etag, String lastModified, String encoding) {
        static Variant of(Path path, BasicFileAttributes attrs, String encoding) {
            long size = attrs.size();
            long mtime = attrs.lastModifiedTime().toMillis();
            return new Variant(path, size, mtime, weakEtag(size, mtime, encoding),
                    formatHttpDate(Instant.ofEpochMilli(mtime)), encoding);
        }
    }

    /**
     * Result of mapping a request path to a file, cached per mount for
     * {@link StaticOptions#revalidateMillis()} so repeated hits skip all stat calls.
     */
    record Resolved(long checkedAtNanos, int status, Variant identity, Variant gzip, Variant br,
                    String contentType, String cacheControl, Path listingDir) {
        boolean isListing() {
            return listingDir != null;
        }
    }

    /** Resolve {@code rel} under {@code root}: one readAttributes per candidate, no separate exists checks. */
    static Resolved resolve(Path root, String rel, StaticOptions opts, long now) {
        Optional<Path> fileOpt = resolveUnderRoot(root, rel);
        if (fileOpt.isEmpty()) {
            return new Resolved(now, 403, null, null, null, null, null, null);
        }
        Path file = fileOpt.get();
        BasicFileAttributes attrs = readAttrs(file);
        if (attrs != null && attrs.isDirectory()) {
            Path dir = file;
            Path index = dir.resolve("index.html").normalize();
            BasicFileAttributes indexAttrs = index.startsWith(dir) ? readAttrs(index) : null;
            if (indexAttrs != null && indexAttrs.isRegularFile()) {
                file = index;
                attrs = indexAttrs;
            } else if (opts.directoryListing()) {
                return new Resolved(now, 200, null, null, null, null, null, dir);
            } else {
                return new Resolved(now, 404, null, null, null, null, null, null);
            }
        }
        if (attrs == null || !attrs.isRegularFile()) {
            return new Resolved(now, 404, null, null, null, null, null, null);
        }
        Variant identity = Variant.of(file, attrs, null);
        Variant gz = null;
        Variant br = null;
        if (opts.precompressed()) {
            Path gzPath = sibling(file, ".gz");
            BasicFileAttributes ga = readAttrs(gzPath);
            if (ga != null && ga.isRegularFile()) {
                gz = Variant.of(gzPath, ga, "gzip");
            }
            Path brPath = sibling(file, ".br");
            BasicFileAttributes ba = readAttrs(brPath);
            if (ba != null && ba.isRegularFile()) {
                br = Variant.of(brPath, ba, "br");
            }
        }
        String fileName = file.getFileName().toString();
        String ct = MimeTypes.forPath(fileName);
        String cc = opts.isImmutable(fileName) ? opts.immutableCacheControl() : opts.defaultCacheControl();
        return new Resolved(now, 200, identity, gz, br,
                ct == null ? "application/octet-stream" : ct, cc, null);
    }

    private static BasicFileAttributes readAttrs(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Serve a resolved file honoring precompressed siblings, conditional GET,
     * Range, the small-file cache, and zero-copy for large bodies.
     */
    static void serveResolved(HttpRequest req, HttpResponse resp, Resolved r,
                              StaticOptions opts, StaticFileCache cache) throws IOException {
        if (r.status() == 403) {
            resp.status(403).text("Forbidden");
            return;
        }
        if (r.status() != 200) {
            resp.status(404).text("Not Found");
            return;
        }
        if (r.isListing()) {
            serveDirectoryListing(req, resp, r.listingDir());
            return;
        }
        String contentType = r.contentType();
        boolean wantsRange = req.method() == HttpMethod.GET && req.header("range").isPresent();
        boolean hasVariants = r.gzip() != null || r.br() != null;

        // 1) Pick the representation. Range requests always use identity bytes.
        Variant v = r.identity();
        if (!wantsRange && hasVariants) {
            String ae = req.header("accept-encoding").orElse("");
            if (r.br() != null && acceptsEncoding(ae, "br")) {
                v = r.br();
            } else if (r.gzip() != null && acceptsEncoding(ae, "gzip")) {
                v = r.gzip();
            }
        }

        resp.header("Content-Type", contentType);
        resp.header("ETag", v.etag());
        resp.header("Last-Modified", v.lastModified());
        if (v.encoding() == null) {
            resp.header("Accept-Ranges", "bytes");
        } else {
            resp.header("Content-Encoding", v.encoding());
            Trace.METRICS.recordStaticPrecompressed();
        }
        if (hasVariants) {
            resp.header("Vary", "Accept-Encoding");
        }
        if (r.cacheControl() != null) {
            resp.header("Cache-Control", r.cacheControl());
        }

        // 2) Conditional GET.
        if (isNotModified(req, v.etag(), Instant.ofEpochMilli(v.mtimeMillis()))) {
            Trace.METRICS.recordStaticNotModified();
            resp.status(304).empty();
            return;
        }

        long size = v.size();
        boolean cacheable = size <= opts.cacheMaxEntryBytes();

        // 3) Single byte range (identity representation only).
        if (wantsRange && size > 0 && size <= Integer.MAX_VALUE) {
            Range range = parseRange(req.header("range").get(), (int) size);
            if (range != null) {
                if (!range.satisfiable()) {
                    resp.status(416).header("Content-Range", "bytes */" + size).empty();
                    return;
                }
                resp.status(206).header("Content-Range",
                        "bytes " + range.start() + "-" + range.end() + "/" + size);
                if (cacheable) {
                    byte[] data = cache.get(v.path(), size, v.mtimeMillis());
                    byte[] slice = new byte[range.length()];
                    System.arraycopy(data, range.start(), slice, 0, range.length());
                    resp.bytes(slice, contentType);
                } else {
                    resp.file(v.path(), range.start(), range.length(), contentType);
                }
                return;
            }
        }

        // 4) Full body: small files from memory, large files via zero-copy file region.
        resp.status(200);
        if (cacheable) {
            resp.bytes(cache.get(v.path(), size, v.mtimeMillis()), contentType);
        } else {
            resp.file(v.path(), 0, size, contentType);
        }
    }

    /** {@code app.js} plus {@code ".gz"} gives {@code app.js.gz} in the same directory. */
    static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString() + suffix);
    }

    /**
     * True when {@code acceptEncoding} lists {@code token} (or {@code *}) with a non-zero q value.
     */
    static boolean acceptsEncoding(String acceptEncoding, String token) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return false;
        }
        boolean wildcard = false;
        for (String part : acceptEncoding.toLowerCase(Locale.ROOT).split(",")) {
            String[] bits = part.trim().split(";");
            String name = bits[0].trim();
            double q = 1.0;
            for (int i = 1; i < bits.length; i++) {
                String b = bits[i].trim();
                if (b.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(b.substring(2).trim());
                    } catch (NumberFormatException ignored) {
                        q = 1.0;
                    }
                }
            }
            if (name.equals(token)) {
                return q > 0.0;
            }
            if (name.equals("*") && q > 0.0) {
                wildcard = true;
            }
        }
        return wildcard;
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
        return weakEtag(size, mtimeMillis, null);
    }

    /** Weak ETag that also names the content encoding, so gzip/br/identity never collide. */
    static String weakEtag(long size, long mtimeMillis, String encoding) {
        String base = Long.toHexString(size) + "-" + Long.toHexString(mtimeMillis);
        if (encoding != null && !encoding.isEmpty()) {
            base = base + "-" + encoding;
        }
        return "W/\"" + base + "\"";
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
     * Safe directory listing: only direct children under the resolved directory,
     * HTML-escaped names, no {@code ..} entries that escape the mount.
     * JSON when {@code Accept} prefers {@code application/json} or {@code ?format=json}.
     */
    static void serveDirectoryListing(HttpRequest req, HttpResponse resp, Path dir) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("\0")
                        || name.equals(".") || name.equals("..")) {
                    continue;
                }
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(child, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                if (a.isDirectory()) {
                    row.put("type", "dir");
                    row.put("size", 0);
                } else if (a.isRegularFile()) {
                    row.put("type", "file");
                    row.put("size", a.size());
                } else {
                    continue;
                }
                entries.add(row);
            }
        }

        boolean wantJson = wantsJsonListing(req);
        if (wantJson) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", req.path());
            body.put("entries", entries);
            resp.status(200)
                    .header("Cache-Control", "no-cache")
                    .json(Json.stringify(body));
            return;
        }

        StringBuilder html = new StringBuilder();
        String titlePath = htmlEscape(req.path());
        html.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>Index of ")
                .append(titlePath).append("</title></head><body>\n");
        html.append("<h1>Index of ").append(titlePath).append("</h1>\n<ul>\n");
        if (!"/".equals(req.path()) && !req.path().isEmpty()) {
            html.append("<li><a href=\"../\">../</a></li>\n");
        }
        for (Map<String, Object> row : entries) {
            String name = String.valueOf(row.get("name"));
            boolean dirEntry = "dir".equals(row.get("type"));
            String href = htmlEscape(name) + (dirEntry ? "/" : "");
            String label = htmlEscape(name) + (dirEntry ? "/" : "");
            html.append("<li><a href=\"").append(href).append("\">").append(label).append("</a>");
            if (!dirEntry) {
                html.append(" <span>").append(row.get("size")).append("</span>");
            }
            html.append("</li>\n");
        }
        html.append("</ul>\n</body></html>\n");
        resp.status(200)
                .header("Cache-Control", "no-cache")
                .html(html.toString());
    }

    static boolean wantsJsonListing(HttpRequest req) {
        if (req.queryParam("format").map(f -> "json".equalsIgnoreCase(f)).orElse(false)) {
            return true;
        }
        String accept = req.header("accept").orElse("");
        String lower = accept.toLowerCase(Locale.ROOT);
        int json = lower.indexOf("application/json");
        int html = lower.indexOf("text/html");
        if (json < 0) {
            return false;
        }
        if (html < 0) {
            return true;
        }
        return json < html;
    }

    static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
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