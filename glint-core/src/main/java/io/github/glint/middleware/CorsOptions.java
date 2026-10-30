package io.github.glint.middleware;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for {@link Middlewares#cors(CorsOptions)}.
 */
public final class CorsOptions {

    private final String allowOrigin;
    private final List<String> allowMethods;
    private final List<String> allowHeaders;
    private final List<String> exposeHeaders;
    private final boolean allowCredentials;
    private final int maxAgeSeconds;
    private final boolean shortCircuitOptions;

    private CorsOptions(Builder b) {
        this.allowOrigin = b.allowOrigin;
        this.allowMethods = List.copyOf(b.allowMethods);
        this.allowHeaders = List.copyOf(b.allowHeaders);
        this.exposeHeaders = List.copyOf(b.exposeHeaders);
        this.allowCredentials = b.allowCredentials;
        this.maxAgeSeconds = b.maxAgeSeconds;
        this.shortCircuitOptions = b.shortCircuitOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Permissive defaults suitable for local demos ({@code *} origin). */
    public static CorsOptions permissive() {
        return builder().build();
    }

    public String allowOrigin() {
        return allowOrigin;
    }

    public List<String> allowMethods() {
        return allowMethods;
    }

    public List<String> allowHeaders() {
        return allowHeaders;
    }

    public List<String> exposeHeaders() {
        return exposeHeaders;
    }

    public boolean allowCredentials() {
        return allowCredentials;
    }

    public int maxAgeSeconds() {
        return maxAgeSeconds;
    }

    public boolean shortCircuitOptions() {
        return shortCircuitOptions;
    }

    public static final class Builder {
        private String allowOrigin = "*";
        private List<String> allowMethods = new ArrayList<>(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
        private List<String> allowHeaders = new ArrayList<>(Arrays.asList(
                "Content-Type", "Authorization", "X-Requested-With", "Range"));
        private List<String> exposeHeaders = new ArrayList<>();
        private boolean allowCredentials = false;
        private int maxAgeSeconds = 86400;
        private boolean shortCircuitOptions = true;

        public Builder allowOrigin(String origin) {
            this.allowOrigin = Objects.requireNonNull(origin, "origin");
            return this;
        }

        public Builder allowMethods(String... methods) {
            this.allowMethods = new ArrayList<>(Arrays.asList(methods));
            return this;
        }

        public Builder allowHeaders(String... headers) {
            this.allowHeaders = new ArrayList<>(Arrays.asList(headers));
            return this;
        }

        public Builder exposeHeaders(String... headers) {
            this.exposeHeaders = new ArrayList<>(Arrays.asList(headers));
            return this;
        }

        public Builder allowCredentials(boolean value) {
            this.allowCredentials = value;
            return this;
        }

        public Builder maxAgeSeconds(int seconds) {
            this.maxAgeSeconds = seconds;
            return this;
        }

        public Builder shortCircuitOptions(boolean value) {
            this.shortCircuitOptions = value;
            return this;
        }

        public CorsOptions build() {
            if (allowCredentials && "*".equals(allowOrigin)) {
                throw new IllegalArgumentException(
                        "CORS credentials cannot be used with Allow-Origin *");
            }
            return new CorsOptions(this);
        }
    }
}