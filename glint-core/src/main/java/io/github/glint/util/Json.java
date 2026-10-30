package io.github.glint.util;

import java.util.Collection;
import java.util.Map;

/**
 * Tiny JSON serializer for Maps/Lists/scalars — enough for diagnostics APIs
 * without third-party dependencies in glint-core.
 */
public final class Json {

    private Json() {
    }

    public static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof String s) {
            return "\"" + escape(s) + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append('"')
                        .append(':').append(stringify(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof Collection<?> col) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object o : col) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(stringify(o));
            }
            sb.append(']');
            return sb.toString();
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(stringify(java.lang.reflect.Array.get(value, i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    public static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
