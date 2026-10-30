package io.github.glint.http;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetAddress;

/**
 * Tunables for {@link VtServer}. All fields have safe defaults for demos;
 * raise limits carefully in production.
 *
 * <p>Hot paths avoid {@code synchronized}; this config is immutable after build.
 * Optional {@link SSLContext} enables HTTPS via an SSLServerSocket.
 */
public final class ServerConfig {

    public static final int DEFAULT_MAX_REQUEST_BODY = 2 * 1024 * 1024; // 2 MiB
    public static final int DEFAULT_MAX_HEADER_LINE = 8192;
    public static final int DEFAULT_IDLE_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_BACKLOG = 4096;
    public static final long DEFAULT_SHUTDOWN_WAIT_MS = 10_000L;
    public static final int DEFAULT_MAX_MULTIPART_PARTS = 64;
    public static final int DEFAULT_MAX_MULTIPART_PART_BYTES = 1 * 1024 * 1024; // 1 MiB

    private final int maxRequestBodyBytes;
    private final int maxHeaderLineBytes;
    private final int idleTimeoutMs;
    private final int backlog;
    private final long shutdownWaitMs;
    private final int maxMultipartParts;
    private final int maxMultipartPartBytes;
    private final SSLContext sslContext;
    private final InetAddress bindAddress;
    private final boolean highResolutionTimer;

    private ServerConfig(Builder b) {
        this.bindAddress = b.bindAddress;
        this.highResolutionTimer = b.highResolutionTimer;
        this.maxRequestBodyBytes = b.maxRequestBodyBytes;
        this.maxHeaderLineBytes = b.maxHeaderLineBytes;
        this.idleTimeoutMs = b.idleTimeoutMs;
        this.backlog = b.backlog;
        this.shutdownWaitMs = b.shutdownWaitMs;
        this.maxMultipartParts = b.maxMultipartParts;
        this.maxMultipartPartBytes = b.maxMultipartPartBytes;
        this.sslContext = b.sslContext;
    }

    public static ServerConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public int maxHeaderLineBytes() {
        return maxHeaderLineBytes;
    }

    public int idleTimeoutMs() {
        return idleTimeoutMs;
    }

    public int backlog() {
        return backlog;
    }

    public long shutdownWaitMs() {
        return shutdownWaitMs;
    }

    public int maxMultipartParts() {
        return maxMultipartParts;
    }

    public int maxMultipartPartBytes() {
        return maxMultipartPartBytes;
    }

    /** When non-null, {@link VtServer} binds an SSL server socket (HTTPS). */
    public SSLContext sslContext() {
        return sslContext;
    }

    public boolean sslEnabled() {
        return sslContext != null;
    }

    /** Local address to bind, or {@code null} for all interfaces (the default). */
    public InetAddress bindAddress() {
        return bindAddress;
    }

    /**
     * Whether the server keeps the Windows timer at 1 ms resolution while running
     * (default true; no effect on other operating systems). See {@link HighResolutionTimer}.
     */
    public boolean highResolutionTimer() {
        return highResolutionTimer;
    }

    public static final class Builder {
        private int maxRequestBodyBytes = DEFAULT_MAX_REQUEST_BODY;
        private int maxHeaderLineBytes = DEFAULT_MAX_HEADER_LINE;
        private int idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
        private int backlog = DEFAULT_BACKLOG;
        private long shutdownWaitMs = DEFAULT_SHUTDOWN_WAIT_MS;
        private int maxMultipartParts = DEFAULT_MAX_MULTIPART_PARTS;
        private int maxMultipartPartBytes = DEFAULT_MAX_MULTIPART_PART_BYTES;
        private SSLContext sslContext;
        private InetAddress bindAddress;
        private boolean highResolutionTimer = true;

        /** Bind to one local address instead of all interfaces ({@code null} = all). */
        public Builder bindAddress(InetAddress address) {
            this.bindAddress = address;
            return this;
        }

        /**
         * Keep the Windows timer at 1 ms resolution while the server runs so
         * virtual-thread sleeps and timeouts are not rounded up to 15.6 ms ticks.
         * Default true; ignored on other operating systems.
         */
        public Builder highResolutionTimer(boolean enabled) {
            this.highResolutionTimer = enabled;
            return this;
        }

        public Builder maxRequestBodyBytes(int v) {
            this.maxRequestBodyBytes = v;
            return this;
        }

        public Builder maxHeaderLineBytes(int v) {
            this.maxHeaderLineBytes = v;
            return this;
        }

        public Builder idleTimeoutMs(int v) {
            this.idleTimeoutMs = v;
            return this;
        }

        public Builder backlog(int v) {
            this.backlog = v;
            return this;
        }

        public Builder shutdownWaitMs(long v) {
            this.shutdownWaitMs = v;
            return this;
        }

        public Builder maxMultipartParts(int v) {
            this.maxMultipartParts = v;
            return this;
        }

        public Builder maxMultipartPartBytes(int v) {
            this.maxMultipartPartBytes = v;
            return this;
        }

        /**
         * Optional TLS. Pass a configured {@link SSLContext} (for example from
         * a keystore) to serve HTTPS. Null keeps plain HTTP.
         */
        public Builder sslContext(SSLContext ctx) {
            this.sslContext = ctx;
            return this;
        }

        public ServerConfig build() {
            if (maxRequestBodyBytes < 0) {
                throw new IllegalArgumentException("maxRequestBodyBytes");
            }
            if (idleTimeoutMs < 0) {
                throw new IllegalArgumentException("idleTimeoutMs");
            }
            if (maxMultipartParts < 1) {
                throw new IllegalArgumentException("maxMultipartParts");
            }
            if (maxMultipartPartBytes < 0) {
                throw new IllegalArgumentException("maxMultipartPartBytes");
            }
            return new ServerConfig(this);
        }
    }
}
