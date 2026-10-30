package io.github.glint.http;

/**
 * Tunables for {@link VtServer}. All fields have safe defaults for demos;
 * raise limits carefully in production.
 *
 * <p>Hot paths avoid {@code synchronized}; this config is immutable after build.
 */
public final class ServerConfig {

    public static final int DEFAULT_MAX_REQUEST_BODY = 2 * 1024 * 1024; // 2 MiB
    public static final int DEFAULT_MAX_HEADER_LINE = 8192;
    public static final int DEFAULT_IDLE_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_BACKLOG = 4096;
    public static final long DEFAULT_SHUTDOWN_WAIT_MS = 10_000L;

    private final int maxRequestBodyBytes;
    private final int maxHeaderLineBytes;
    private final int idleTimeoutMs;
    private final int backlog;
    private final long shutdownWaitMs;

    private ServerConfig(Builder b) {
        this.maxRequestBodyBytes = b.maxRequestBodyBytes;
        this.maxHeaderLineBytes = b.maxHeaderLineBytes;
        this.idleTimeoutMs = b.idleTimeoutMs;
        this.backlog = b.backlog;
        this.shutdownWaitMs = b.shutdownWaitMs;
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

    public static final class Builder {
        private int maxRequestBodyBytes = DEFAULT_MAX_REQUEST_BODY;
        private int maxHeaderLineBytes = DEFAULT_MAX_HEADER_LINE;
        private int idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
        private int backlog = DEFAULT_BACKLOG;
        private long shutdownWaitMs = DEFAULT_SHUTDOWN_WAIT_MS;

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

        public ServerConfig build() {
            if (maxRequestBodyBytes < 0) {
                throw new IllegalArgumentException("maxRequestBodyBytes");
            }
            if (idleTimeoutMs < 0) {
                throw new IllegalArgumentException("idleTimeoutMs");
            }
            return new ServerConfig(this);
        }
    }
}
