package io.github.glint.http.sse;

import java.util.Objects;

/**
 * One Server-Sent Event (SSE) frame: optional {@code event}, {@code id}, {@code retry},
 * and one or more {@code data} lines (see HTML Living Standard, Server-Sent Events).
 */
public final class SseEvent {

    private final String event;
    private final String id;
    private final String data;
    private final Long retryMillis;

    private SseEvent(Builder b) {
        this.event = b.event;
        this.id = b.id;
        this.data = b.data == null ? "" : b.data;
        this.retryMillis = b.retryMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience: a data-only event. */
    public static SseEvent data(String data) {
        return builder().data(data).build();
    }

    public String event() {
        return event;
    }

    public String id() {
        return id;
    }

    public String data() {
        return data;
    }

    public Long retryMillis() {
        return retryMillis;
    }

    /**
     * Wire form ending with a blank line. Multi-line {@code data} becomes multiple
     * {@code data:} fields. Null event/id/retry are omitted.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        if (id != null) {
            sb.append("id: ").append(sanitizeField(id)).append("\n");
        }
        if (event != null) {
            sb.append("event: ").append(sanitizeField(event)).append("\n");
        }
        if (retryMillis != null) {
            sb.append("retry: ").append(retryMillis).append("\n");
        }
        if (data.isEmpty()) {
            sb.append("data: \n");
        } else {
            int start = 0;
            while (start <= data.length()) {
                int nl = data.indexOf('\n', start);
                String line = nl < 0 ? data.substring(start) : data.substring(start, nl);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                sb.append("data: ").append(line).append('\n');
                if (nl < 0) {
                    break;
                }
                start = nl + 1;
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Strip CR/LF so a field cannot inject extra SSE frames. */
    static String sanitizeField(String value) {
        Objects.requireNonNull(value, "value");
        return value.replace("\r", "").replace("\n", "");
    }

    public static final class Builder {
        private String event;
        private String id;
        private String data;
        private Long retryMillis;

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder data(String data) {
            this.data = data;
            return this;
        }

        /** Client reconnection delay hint in milliseconds. */
        public Builder retry(long millis) {
            if (millis < 0) {
                throw new IllegalArgumentException("retry must be >= 0");
            }
            this.retryMillis = millis;
            return this;
        }

        public SseEvent build() {
            return new SseEvent(this);
        }
    }
}