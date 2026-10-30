package io.github.glint.http.staticfiles;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tuning knobs for directory static mounts.
 *
 * <p>Defaults are chosen for typical web assets:
 * <ul>
 *   <li>Files up to {@code 256 KiB} are cached in memory (keyed by path, invalidated
 *       when size or mtime changes); larger files are streamed with zero-copy
 *       {@code FileChannel.transferTo} when the connection allows it.</li>
 *   <li>Precompressed siblings ({@code app.js.br}, {@code app.js.gz}) are served when
 *       the client accepts that encoding. Nothing is compressed at runtime here.</li>
 *   <li>File names that look fingerprinted ({@code app.3f2a9c1d.js},
 *       {@code vendor-0123abcd.min.css}) get
 *       {@code Cache-Control: public, max-age=31536000, immutable}.</li>
 * </ul>
 */
public final class StaticOptions {

    /** Matches names such as {@code app.3f2a9c1d.js} or {@code main-0a1b2c3d4e.min.css}. */
    public static final Pattern DEFAULT_FINGERPRINT =
            Pattern.compile("^.+[.-][0-9a-fA-F]{8,}(\\.[A-Za-z0-9]+)+$");

    public static final int DEFAULT_CACHE_MAX_ENTRY_BYTES = 256 * 1024;
    public static final long DEFAULT_CACHE_MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_IMMUTABLE_MAX_AGE = 31_536_000L; // one year

    private final int cacheMaxEntryBytes;
    private final long cacheMaxTotalBytes;
    private final boolean precompressed;
    private final Pattern immutablePattern;
    private final boolean immutableAll;
    private final long immutableMaxAgeSeconds;
    private final String defaultCacheControl;
    private final long revalidateMillis;
    private final boolean directoryListing;

    private StaticOptions(Builder b) {
        this.cacheMaxEntryBytes = b.cacheMaxEntryBytes;
        this.cacheMaxTotalBytes = b.cacheMaxTotalBytes;
        this.precompressed = b.precompressed;
        this.immutablePattern = b.immutablePattern;
        this.immutableAll = b.immutableAll;
        this.immutableMaxAgeSeconds = b.immutableMaxAgeSeconds;
        this.defaultCacheControl = b.defaultCacheControl;
        this.revalidateMillis = b.revalidateMillis;
        this.directoryListing = b.directoryListing;
    }

    public static StaticOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Files at or below this size are served from the in-memory cache (0 disables caching). */
    public int cacheMaxEntryBytes() {
        return cacheMaxEntryBytes;
    }

    /** Soft upper bound for all cached bytes of one mount. */
    public long cacheMaxTotalBytes() {
        return cacheMaxTotalBytes;
    }

    /** Serve {@code .br} / {@code .gz} siblings when present and accepted. */
    public boolean precompressed() {
        return precompressed;
    }

    public long immutableMaxAgeSeconds() {
        return immutableMaxAgeSeconds;
    }

    /** Cache-Control for non-versioned files, or {@code null} to omit the header. */
    public String defaultCacheControl() {
        return defaultCacheControl;
    }

    /**
     * How long a resolved path (file attributes, siblings, validators) is trusted before
     * the filesystem is checked again. Default 1000 ms; 0 re-checks on every request.
     * Edits become visible after at most this interval.
     */
    public long revalidateMillis() {
        return revalidateMillis;
    }

    /**
     * When true, directories without {@code index.html} return a simple HTML or JSON
     * listing (off by default). Path traversal is still rejected.
     */
    public boolean directoryListing() {
        return directoryListing;
    }

    /** True when {@code fileName} should receive the long-lived immutable cache header. */
    public boolean isImmutable(String fileName) {
        if (immutableAll) {
            return true;
        }
        return immutablePattern != null && fileName != null && immutablePattern.matcher(fileName).matches();
    }

    /** Header value used for versioned assets. */
    public String immutableCacheControl() {
        return "public, max-age=" + immutableMaxAgeSeconds + ", immutable";
    }

    public static final class Builder {
        private int cacheMaxEntryBytes = DEFAULT_CACHE_MAX_ENTRY_BYTES;
        private long cacheMaxTotalBytes = DEFAULT_CACHE_MAX_TOTAL_BYTES;
        private boolean precompressed = true;
        private Pattern immutablePattern = DEFAULT_FINGERPRINT;
        private boolean immutableAll;
        private long immutableMaxAgeSeconds = DEFAULT_IMMUTABLE_MAX_AGE;
        private String defaultCacheControl;
        private long revalidateMillis = 1000L;
        private boolean directoryListing;

        /** See {@link StaticOptions#revalidateMillis()}. */
        public Builder revalidateMillis(long v) {
            if (v < 0) {
                throw new IllegalArgumentException("revalidateMillis must be >= 0");
            }
            this.revalidateMillis = v;
            return this;
        }

        public Builder cacheMaxEntryBytes(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("cacheMaxEntryBytes must be >= 0");
            }
            this.cacheMaxEntryBytes = v;
            return this;
        }

        public Builder cacheMaxTotalBytes(long v) {
            if (v < 0) {
                throw new IllegalArgumentException("cacheMaxTotalBytes must be >= 0");
            }
            this.cacheMaxTotalBytes = v;
            return this;
        }

        public Builder precompressed(boolean v) {
            this.precompressed = v;
            return this;
        }

        /** Regex applied to the file name; {@code null} disables pattern detection. */
        public Builder immutablePattern(Pattern p) {
            this.immutablePattern = p;
            return this;
        }

        /** Treat every file of this mount as versioned (for example a hashed build output dir). */
        public Builder immutable(boolean all) {
            this.immutableAll = all;
            return this;
        }

        public Builder immutableMaxAgeSeconds(long v) {
            if (v < 0) {
                throw new IllegalArgumentException("immutableMaxAgeSeconds must be >= 0");
            }
            this.immutableMaxAgeSeconds = v;
            return this;
        }

        public Builder defaultCacheControl(String v) {
            this.defaultCacheControl = v;
            return this;
        }

        /** Enable a safe HTML/JSON directory listing when no index.html is present. */
        public Builder directoryListing(boolean enabled) {
            this.directoryListing = enabled;
            return this;
        }

        public StaticOptions build() {
            return new StaticOptions(this);
        }
    }

    @Override
    public String toString() {
        return "StaticOptions{cacheMaxEntryBytes=" + cacheMaxEntryBytes
                + ", precompressed=" + precompressed
                + ", immutable=" + (immutableAll ? "all" : Objects.toString(immutablePattern)) + "}";
    }
}
